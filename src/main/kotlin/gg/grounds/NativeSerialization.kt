package gg.grounds

import gg.grounds.api.CatalogReferenceDto
import gg.grounds.api.CommitVersionRequest
import gg.grounds.api.CreateMapRequest
import gg.grounds.api.DeriveProblemDto
import gg.grounds.api.ForkRequest
import gg.grounds.api.HeadDto
import gg.grounds.api.HeadFileDto
import gg.grounds.api.HeadSnapshotDto
import gg.grounds.api.LivePinDto
import gg.grounds.api.LodPreparationDto
import gg.grounds.api.MapDto
import gg.grounds.api.MissingBlobDto
import gg.grounds.api.MovePinRequest
import gg.grounds.api.OpenSnapshotRequest
import gg.grounds.api.OpenedSnapshotDto
import gg.grounds.api.PinDto
import gg.grounds.api.PreparationDto
import gg.grounds.api.ProblemDto
import gg.grounds.api.PublishVersionRequest
import gg.grounds.api.SceneDto
import gg.grounds.api.UploadDto
import gg.grounds.api.VersionDto
import gg.grounds.derive.AssetCatalogCandidate
import gg.grounds.derive.DeriveFailure
import gg.grounds.derive.DeriveRequest
import gg.grounds.derive.DeriveResult
import gg.grounds.derive.DeriveSuccess
import gg.grounds.derive.DerivedManifest
import gg.grounds.derive.DerivedScene
import gg.grounds.domain.CatalogReference
import gg.grounds.domain.DeriveProblem
import gg.grounds.domain.WorkspaceBlob
import gg.grounds.domain.WorkspaceFile
import gg.grounds.lit.LitRequest
import gg.grounds.lit.LitResult
import gg.grounds.lod.format.LodJobRequest
import gg.grounds.lod.format.LodJobResult
import gg.grounds.lod.format.LodManifest
import gg.grounds.lod.format.LodPayloadGrant
import gg.grounds.lod.format.LodPayloadRequest
import gg.grounds.lod.format.LodServingDiscoveryRequest
import gg.grounds.lod.format.LodServingSelection
import gg.grounds.lod.format.LodSourceGrant
import gg.grounds.lod.format.LodSourceRequest
import gg.grounds.lod.format.LodTile
import gg.grounds.pins.LitEntry
import gg.grounds.pins.LodArtifactRef
import gg.grounds.pins.LodEntry
import gg.grounds.pins.PinFile
import gg.grounds.pins.PinnedEntry
import io.quarkus.runtime.annotations.RegisterForReflection

// Response.entity(), private S3 result markers, JSONB manifests and pin-file projections are
// outside REST's typed request/return discovery. Register their complete nested contracts.
@RegisterForReflection(
    targets =
        [
            CreateMapRequest::class,
            MapDto::class,
            ProblemDto::class,
            UploadDto::class,
            CommitVersionRequest::class,
            PublishVersionRequest::class,
            ForkRequest::class,
            MovePinRequest::class,
            VersionDto::class,
            PreparationDto::class,
            LodPreparationDto::class,
            SceneDto::class,
            CatalogReferenceDto::class,
            DeriveProblemDto::class,
            LivePinDto::class,
            PinDto::class,
            OpenSnapshotRequest::class,
            MissingBlobDto::class,
            OpenedSnapshotDto::class,
            HeadDto::class,
            HeadFileDto::class,
            HeadSnapshotDto::class,
            WorkspaceFile::class,
            WorkspaceBlob::class,
            PinFile::class,
            PinnedEntry::class,
            LitEntry::class,
            LodEntry::class,
            LodArtifactRef::class,
            LodManifest::class,
            LodTile::class,
            LodJobRequest::class,
            LodPayloadRequest::class,
            LodPayloadGrant::class,
            LodSourceRequest::class,
            LodSourceGrant::class,
            LodServingDiscoveryRequest::class,
            LodServingSelection::class,
            LodJobResult::class,
            LitRequest::class,
            LitResult::class,
            DeriveRequest::class,
            gg.grounds.domain.SceneShapeRequirements::class,
            AssetCatalogCandidate::class,
            DeriveResult::class,
            DeriveSuccess::class,
            DeriveFailure::class,
            DerivedScene::class,
            DerivedManifest::class,
            CatalogReference::class,
            DeriveProblem::class,
        ]
)
class NativeSerialization
