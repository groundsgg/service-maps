package gg.grounds.openapi

import jakarta.ws.rs.ApplicationPath
import jakarta.ws.rs.core.Application
import org.eclipse.microprofile.openapi.annotations.OpenAPIDefinition
import org.eclipse.microprofile.openapi.annotations.enums.SecuritySchemeType
import org.eclipse.microprofile.openapi.annotations.info.Info
import org.eclipse.microprofile.openapi.annotations.security.SecurityScheme
import org.eclipse.microprofile.openapi.annotations.tags.Tag

/**
 * What the published API reference says about this service.
 *
 * The description carries the two facts a reader has to know before calling anything, because both
 * are surprising and neither is visible from an endpoint list: bundles never travel through this
 * API, and publishing a version is not the same as putting it in front of players.
 */
@ApplicationPath("/")
@OpenAPIDefinition(
    info =
        Info(
            title = "Maps API",
            version = "1.0.0",
            description =
                "The registry for Minecraft maps: what exists, which versions were published, and " +
                    "which version each environment serves.\n\n" +
                    "**Bundles do not pass through this API.** An upload is a presigned URL the " +
                    "client PUTs to directly, and game servers read published content from the " +
                    "CDN — they never call this service.\n\n" +
                    "**Publishing is not going live.** A published version is merely pinnable; " +
                    "moving a pin is what changes the map every player loads, and it is granted " +
                    "separately.",
        ),
    tags =
        [
            Tag(name = "Maps", description = "The catalogue: creating, listing and forking maps."),
            Tag(
                name = "Versions",
                description = "Uploading a world, committing a version and publishing it.",
            ),
            Tag(
                name = "Pins",
                description = "Which version an environment serves. Going live and rolling back.",
            ),
        ],
)
@SecurityScheme(
    securitySchemeName = "keycloakBearer",
    type = SecuritySchemeType.HTTP,
    scheme = "bearer",
    bearerFormat = "JWT",
    description =
        "A Keycloak access token carrying `aud: service-maps`. Authorisation is by the `groups` " +
            "claim, or by owning the `u/<sub>` namespace — except moving a pin, which is never " +
            "granted by ownership.",
)
class OpenApiConfiguration : Application()
