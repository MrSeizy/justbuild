package dev.justbuild.config;

import com.sun.jna.Platform;
import com.sun.jna.platform.win32.AccCtrl;
import com.sun.jna.platform.win32.Advapi32;
import com.sun.jna.platform.win32.Advapi32Util;
import com.sun.jna.platform.win32.WinNT;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.AclEntry;
import java.nio.file.attribute.AclEntryPermission;
import java.nio.file.attribute.AclEntryType;
import java.nio.file.attribute.AclFileAttributeView;
import java.nio.file.attribute.FileAttribute;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.EnumSet;
import java.util.List;

/** Strict private vault files, plus best-effort permissions for configs that contain only encrypted credentials. */
final class PrivateFiles {
    private PrivateFiles() { }

    static void directory(Path directory) throws IOException {
        boolean existed = Files.exists(directory, LinkOption.NOFOLLOW_LINKS);
        Files.createDirectories(directory, attributes(directory, true));
        if (!existed) protectWindowsAcl(directory);
        check(directory);
    }

    static void create(Path file, byte[] bytes) throws IOException {
        Files.createFile(file, attributes(file, false));
        try { protectWindowsAcl(file); check(file); Files.write(file, bytes); }
        catch (IOException e) { Files.deleteIfExists(file); throw e; }
    }

    /** The config holds ciphertext, so restrictive permissions here are best effort on shared/FAT mounts. */
    static Path configTemporary(Path directory, String prefix) throws IOException {
        Path file;
        try { file = Files.createTempFile(directory, prefix, ".tmp", attributes(directory, false)); }
        catch (IOException | UnsupportedOperationException e) { file = Files.createTempFile(directory, prefix, ".tmp"); }
        try { protectWindowsAcl(file); } catch (IOException ignored) { /* The vault remains strict. */ }
        return file;
    }

    /** Java's ACL view cannot disable inherited entries. Protect new empty files before writing bytes. */
    private static void protectWindowsAcl(Path path) throws IOException {
        if (!Platform.isWindows()) return;
        try {
            var descriptor = Advapi32Util.getFileSecurityDescriptor(path.toFile(), false);
            var acl = new WinNT.ACL(1024); // More than enough for one owner SID and its ACE.
            if (!Advapi32.INSTANCE.InitializeAcl(acl, 1024, WinNT.ACL_REVISION)
                    || !Advapi32.INSTANCE.AddAccessAllowedAce(acl, WinNT.ACL_REVISION,
                    WinNT.FILE_ALL_ACCESS, descriptor.getOwner())) {
                throw new IOException("Could not construct an owner-only credential ACL");
            }
            int result = Advapi32.INSTANCE.SetNamedSecurityInfo(path.toAbsolutePath().toString(),
                    AccCtrl.SE_OBJECT_TYPE.SE_FILE_OBJECT,
                    WinNT.DACL_SECURITY_INFORMATION | WinNT.PROTECTED_DACL_SECURITY_INFORMATION,
                    null, null, acl.getPointer(), null);
            if (result != 0) throw new IOException("Could not protect the credential ACL (Windows error " + result + ")");
        } catch (RuntimeException | LinkageError e) {
            throw new IOException("Could not protect the credential ACL");
        }
    }

    static void check(Path file) throws IOException {
        if (Files.isSymbolicLink(file)) throw new IOException("A credential vault path cannot be a symbolic link");
        if (file.getFileSystem().supportedFileAttributeViews().contains("posix")) {
            var permissions = Files.getPosixFilePermissions(file, LinkOption.NOFOLLOW_LINKS);
            if (permissions.stream().anyMatch(p -> p.name().startsWith("GROUP_") || p.name().startsWith("OTHERS_"))) {
                throw new IOException("The credential vault must be accessible only to its owner");
            }
        } else if (file.getFileSystem().supportedFileAttributeViews().contains("acl")) {
            var owner = Files.getOwner(file, LinkOption.NOFOLLOW_LINKS);
            for (var entry : Files.getFileAttributeView(file, AclFileAttributeView.class, LinkOption.NOFOLLOW_LINKS).getAcl()) {
                if (entry.type() == AclEntryType.ALLOW && !entry.principal().equals(owner)) {
                    throw new IOException("The credential vault must be accessible only to its owner");
                }
            }
        }
    }

    private static FileAttribute<?>[] attributes(Path path, boolean directory) throws IOException {
        var views = path.getFileSystem().supportedFileAttributeViews();
        if (views.contains("posix")) return new FileAttribute<?>[] {
                PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString(directory ? "rwx------" : "rw-------")) };
        if (views.contains("acl")) {
            var owner = Files.getOwner(Path.of(System.getProperty("user.home")));
            List<AclEntry> acl = List.of(AclEntry.newBuilder().setType(AclEntryType.ALLOW).setPrincipal(owner)
                    .setPermissions(EnumSet.allOf(AclEntryPermission.class)).build());
            return new FileAttribute<?>[] { new FileAttribute<List<AclEntry>>() {
                @Override public String name() { return "acl:acl"; }
                @Override public List<AclEntry> value() { return acl; }
            } };
        }
        throw new IOException("This file system cannot restrict access to the credential vault");
    }
}
