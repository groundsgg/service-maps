package gg.grounds.authz

import gg.grounds.domain.MapAddress
import io.quarkus.security.identity.SecurityIdentity
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import org.eclipse.microprofile.config.inject.ConfigProperty

/**
 * Who may do what.
 *
 * Two mechanisms, deliberately not one:
 * - **Keycloak groups** decide staff actions. The token already carries a `groups` claim — it is
 *   what `grounds-portal` authorises on — and Quarkus maps it into the identity's roles. Which
 *   group grants which action is *configuration*, so adding a build team does not need a release of
 *   this service.
 * - **Ownership** decides creator actions. A subject may always act inside its own `u/<sub>`
 *   namespace, with no group at all. That is what lets `grounds create` hand a creator their own
 *   corner without minting a Keycloak group per person.
 *
 * `service-permissions` is deliberately *not* in this path. That service answers what a player may
 * do in-game, scoped per environment; using it to gate an HTTP API for staff would be the same word
 * for two different questions.
 *
 * Publishing and going live are separate on purpose. Publishing a version is safe — it changes
 * nothing players see — while moving a pin changes what everyone loads, which is exactly why one is
 * cheap and the other is gated.
 */
@ApplicationScoped
class Authorizer
@Inject
constructor(
    private val identity: SecurityIdentity,
    /** Create maps, upload, commit versions, fork. */
    @ConfigProperty(name = "grounds.maps.authz.author-groups")
    private val authorGroups: List<String>,
    /** Mark a version published: it becomes pinnable, but nobody is looking at it yet. */
    @ConfigProperty(name = "grounds.maps.authz.publish-groups")
    private val publishGroups: List<String>,
    /** Move a pin. This is the one that changes what every player loads. */
    @ConfigProperty(name = "grounds.maps.authz.golive-groups")
    private val goliveGroups: List<String>,
    /** See everything, including other creators' unpublished work. */
    @ConfigProperty(name = "grounds.maps.authz.review-groups")
    private val reviewGroups: List<String>,
) {

    val subject: String
        get() = identity.principal.name

    /** The namespace a subject owns outright. */
    private val ownNamespace: String
        get() = "u/$subject"

    fun mayAuthor(address: MapAddress): Boolean =
        address.namespace == ownNamespace || inAny(authorGroups)

    fun mayPublish(address: MapAddress): Boolean =
        address.namespace == ownNamespace || inAny(publishGroups)

    /**
     * Going live is never granted by ownership. A creator publishing into their own namespace still
     * cannot decide what an environment serves — that is the whole point of the split between
     * publishing and pinning.
     */
    fun mayGoLive(): Boolean = inAny(goliveGroups)

    /** Reading someone else's work needs a group; reading your own never does. */
    fun maySee(ownerSub: String, namespace: String): Boolean =
        ownerSub == subject || !namespace.startsWith("u/") || inAny(reviewGroups)

    private fun inAny(groups: List<String>): Boolean = groups.any(identity::hasRole)
}
