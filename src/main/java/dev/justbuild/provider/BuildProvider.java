package dev.justbuild.provider;

import java.util.List;

/** One AI backend. Every method but the names blocks; call them off the server and render threads. */
public interface BuildProvider {
    String id();

    String displayName();

    /** Returns the model's raw JSON text for the shared build-plan schema, reporting progress as it goes. */
    String complete(PlanRequest request, CancellationToken token, ProgressListener progress) throws ProviderException;

    default String complete(PlanRequest request, CancellationToken token) throws ProviderException {
        return complete(request, token, ProgressListener.NONE);
    }

    /** One-line health and login check for /justbuild doctor. */
    String check(CancellationToken token) throws ProviderException;

    /** Model names this backend accepts, for the profile editor's picker and /justbuild models. */
    List<String> models(CancellationToken token) throws ProviderException;
}
