package dev.justbuild.prompt;

import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.*;
import java.util.*;
import java.util.List;
import javax.imageio.ImageIO;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.EmptyBlockGetter;

/** Small, deterministic, headless views of bounded game data. No screenshots, files, or external renderer. */
public final class PlanImages {
    private PlanImages() { }
    public static List<byte[]> render(PlanningFeedback feedback) {
        return render(feedback, false);
    }
    /** {@code elevations} adds the candidate's four facades, for the separate reviewer and its repair. */
    public static List<byte[]> render(PlanningFeedback feedback, boolean elevations) {
        List<byte[]> images=new ArrayList<>();
        if(feedback.source()!=null)images.add(render(feedback.source(),"ORIGINAL source"));
        if(feedback.candidate()!=null)images.add(render(feedback.candidate(),"CANDIDATE draft"));
        if(elevations&&feedback.candidate()!=null)images.add(elevations(feedback.candidate()));
        return List.copyOf(images);
    }
    public static byte[] render(VoxelVolume volume,String label) {
        BufferedImage image=new BufferedImage(768,640,BufferedImage.TYPE_INT_RGB);
        Graphics2D g=image.createGraphics();
        try {
            g.setColor(new Color(0x151b23));g.fillRect(0,0,768,640);
            g.setFont(new Font(Font.MONOSPACED,Font.PLAIN,12));g.setColor(Color.WHITE);
            g.drawString(label+" | block-color cubes | sample spacing "+volume.step(),14,21);
            var from=volume.box().from();var to=volume.box().to();
            g.drawString("x "+from.x()+".."+to.x()+"  y "+from.y()+".."+to.y()+"  z "+from.z()+".."+to.z(),14,39);
            g.drawString("Dark background = air or unknown; unsampled gaps are unknown. +x east, +y up, +z south.",14,57);
            Map<String,Color> colors=colors(volume);
            g.drawString("Isometric from southeast",14,83);
            double scale=Math.min(340.0/(volume.nx()+volume.nz()),220.0/(volume.ny()+(volume.nx()+volume.nz())*.25));
            int cx=190,cy=295;
            for(int y=0;y<volume.ny();y++)for(int z=0;z<volume.nz();z++)for(int x=0;x<volume.nx();x++) {
                String state=volume.at(x,y,z);if(empty(state))continue;
                double px=cx+(x-z)*scale*.5,py=cy+(x+z-volume.nx()-volume.nz())*scale*.25-y*scale*.65;
                Color color=colors.get(state);
                polygon(g,color.brighter(),px,py-scale*.65,scale,new double[][]{{0,0},{.5,.25},{0,.5},{-.5,.25}});
                polygon(g,color,px,py-scale*.65,scale,new double[][]{{.5,.25},{.5,.9},{0,1.15},{0,.5}});
                polygon(g,color.darker(),px,py-scale*.65,scale,new double[][]{{-.5,.25},{0,.5},{0,1.15},{-.5,.9}});
            }
            g.setColor(Color.WHITE);g.drawString("Top: +x right, +z down",400,83);
            double topScale=Math.min(340.0/volume.nx(),230.0/volume.nz());
            for(int z=0;z<volume.nz();z++)for(int x=0;x<volume.nx();x++)for(int y=volume.ny()-1;y>=0;y--){String s=volume.at(x,y,z);if(!empty(s)){rect(g,colors.get(s),400+x*topScale,94+z*topScale,topScale);break;}}
            int midZ=volume.nz()/2,midX=volume.nx()/2;
            g.setColor(Color.WHITE);g.drawString("Section z="+volume.position(0,0,midZ).z()+"; +x right, +y up",14,356);
            g.drawString("Section x="+volume.position(midX,0,0).x()+"; +z right, +y up",400,356);
            double sx=Math.min(340.0/volume.nx(),240.0/volume.ny()),sz=Math.min(340.0/volume.nz(),240.0/volume.ny());
            for(int y=0;y<volume.ny();y++) {
                for(int x=0;x<volume.nx();x++){String s=volume.at(x,y,midZ);if(!empty(s))rect(g,colors.get(s),14+x*sx,370+(volume.ny()-1-y)*sx,sx);}
                for(int z=0;z<volume.nz();z++){String s=volume.at(midX,y,z);if(!empty(s))rect(g,colors.get(s),400+z*sz,370+(volume.ny()-1-y)*sz,sz);}
            }
        } finally {g.dispose();}
        return png(image);
    }
    /** Four straight-on facades of a volume: the first block seen from each side, darker the farther it is set back. */
    public static byte[] elevations(VoxelVolume volume) {
        BufferedImage image=new BufferedImage(768,640,BufferedImage.TYPE_INT_RGB);
        Graphics2D g=image.createGraphics();
        try {
            g.setColor(new Color(0x151b23));g.fillRect(0,0,768,640);
            g.setFont(new Font(Font.MONOSPACED,Font.PLAIN,12));g.setColor(Color.WHITE);
            g.drawString("CANDIDATE facades | block-color, darker = set back; unknown cells drawn as empty | sample spacing "+volume.step(),14,21);
            Map<String,Color> colors=colors(volume);
            String[] names={"South (seen from +z)","East (seen from +x)","North (seen from -z)","West (seen from -x)"};
            for(int side=0;side<4;side++) {
                int ox=side%2==0?14:400,oy=side<2?40:340;
                g.setColor(Color.WHITE);g.drawString(names[side],ox,oy+12);
                facade(g,volume,side,colors,ox,oy+20);
            }
        } finally {g.dispose();}
        return png(image);
    }
    /** side 0..3 = south, east, north, west; columns run left to right as a viewer outside that side sees them.
     *  Depth is counted in samples from the viewer's side, and shading is relative to the facade's nearest block. */
    private static void facade(Graphics2D g,VoxelVolume v,int side,Map<String,Color> colors,int ox,int oy) {
        boolean alongX=side%2==0;
        int width=alongX?v.nx():v.nz(),deep=alongX?v.nz():v.nx();
        int[][] depth=new int[width][v.ny()];String[][] seen=new String[width][v.ny()];int nearest=Integer.MAX_VALUE;
        for(int u=0;u<width;u++)for(int y=0;y<v.ny();y++)for(int k=0;k<deep;k++) {
            int x=switch(side){case 0->u;case 1->v.nx()-1-k;case 2->v.nx()-1-u;default->k;};
            int z=switch(side){case 0->v.nz()-1-k;case 1->v.nz()-1-u;case 2->k;default->u;};
            String s=v.at(x,y,z);
            if(!empty(s)){seen[u][y]=s;depth[u][y]=k;nearest=Math.min(nearest,k);break;}
        }
        double scale=Math.min(354.0/width,270.0/v.ny());
        for(int u=0;u<width;u++)for(int y=0;y<v.ny();y++) {
            if(seen[u][y]==null)continue;
            Color c=lift(colors.get(seen[u][y]));double f=0.45+0.55/(1+0.4*(depth[u][y]-nearest));
            rect(g,new Color((int)(c.getRed()*f),(int)(c.getGreen()*f),(int)(c.getBlue()*f)),ox+u*scale,oy+(v.ny()-1-y)*scale,scale);
        }
    }
    /** Near-black block colors would vanish into the background, so dark ones are blended halfway toward grey. */
    private static Color lift(Color c){return 0.299*c.getRed()+0.587*c.getGreen()+0.114*c.getBlue()<80?new Color((c.getRed()+0xa0)/2,(c.getGreen()+0xa0)/2,(c.getBlue()+0xa0)/2):c;}
    private static Map<String,Color> colors(VoxelVolume volume) {
        Map<String,Color> colors=new HashMap<>();
        for(String state:volume.palette()) {
            int rgb=dev.justbuild.world.BlockResolver.parse(state).map(s->s.getMapColor(EmptyBlockGetter.INSTANCE,BlockPos.ZERO).col).orElse(0x888888);
            colors.put(state,new Color(rgb==0?0x888888:rgb&0xffffff));
        }
        return colors;
    }
    private static byte[] png(BufferedImage image) {
        try {ByteArrayOutputStream out=new ByteArrayOutputStream();ImageIO.write(image,"png",out);return out.toByteArray();}
        catch(IOException e){throw new IllegalStateException("Could not render planning image",e);}
    }
    private static boolean empty(String state){return state==null||state.equals("minecraft:air")||state.equals("minecraft:cave_air")||state.equals("minecraft:void_air");}
    private static void rect(Graphics2D g,Color color,double x,double y,double s){g.setColor(color);g.fillRect((int)x,(int)y,Math.max(1,(int)Math.ceil(s)),Math.max(1,(int)Math.ceil(s)));}
    private static void polygon(Graphics2D g,Color color,double x,double y,double s,double[][] points){int[] xs=new int[points.length],ys=new int[points.length];for(int i=0;i<points.length;i++){xs[i]=(int)(x+points[i][0]*s);ys[i]=(int)(y+points[i][1]*s);}g.setColor(color);g.fillPolygon(xs,ys,points.length);}
}
