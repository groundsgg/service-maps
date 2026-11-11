# Changelog

## [1.16.1](https://github.com/groundsgg/service-maps/compare/v1.16.0...v1.16.1) (2026-11-11)


### Bug Fixes

* **lod:** expose safe per-version generation progress ([#119](https://github.com/groundsgg/service-maps/issues/119)) ([2e74b2f](https://github.com/groundsgg/service-maps/commit/2e74b2fc238c3a110478a480b19ae0ec82389eb2))

## [1.16.0](https://github.com/groundsgg/service-maps/compare/v1.15.0...v1.16.0) (2026-11-10)


### Features

* **lod:** prepare stage maps and serve entitled players ([#115](https://github.com/groundsgg/service-maps/issues/115)) ([b7a50b8](https://github.com/groundsgg/service-maps/commit/b7a50b8e2cb39e00ab1ed772bb00f0bdbe3167c2))

## [1.15.0](https://github.com/groundsgg/service-maps/compare/v1.14.0...v1.15.0) (2026-11-10)


### Features

* **maps:** add versioned lod generation and delivery ([86cdeb6](https://github.com/groundsgg/service-maps/commit/86cdeb655ff3f9683b035e16886f5dcb8949fe87))

## [1.14.0](https://github.com/groundsgg/service-maps/compare/v1.13.1...v1.14.0) (2026-11-09)


### Features

* **maps:** synchronize compatible catalogs and guard live activation ([#108](https://github.com/groundsgg/service-maps/issues/108)) ([efc67c7](https://github.com/groundsgg/service-maps/commit/efc67c78cbb15d798f621481855da803d19d2ea7))

## [1.13.1](https://github.com/groundsgg/service-maps/compare/v1.13.0...v1.13.1) (2026-11-09)


### Bug Fixes

* **maps:** retain editor catalogs alongside published versions ([8308cb4](https://github.com/groundsgg/service-maps/commit/8308cb45c9b68886355840315cfc9398ff47cfcd))

## [1.13.0](https://github.com/groundsgg/service-maps/compare/v1.12.0...v1.13.0) (2026-11-09)


### Features

* **maps:** prepare published maps before live activation ([b5df64d](https://github.com/groundsgg/service-maps/commit/b5df64dcd51783c5d81fad0500c20ef6457918cb))

## [1.12.0](https://github.com/groundsgg/service-maps/compare/v1.11.1...v1.12.0) (2026-11-08)


### Features

* **catalogs:** retain bounded immutable operator pins alongside channels ([#100](https://github.com/groundsgg/service-maps/issues/100)) ([b783133](https://github.com/groundsgg/service-maps/commit/b783133d0ba79508caed677313c3be7b3f21e3d1))


### Bug Fixes

* **deps:** bump gg.grounds:resourcepacks-client from 1.0.0 to 1.3.0 ([#99](https://github.com/groundsgg/service-maps/issues/99)) ([2d0fb41](https://github.com/groundsgg/service-maps/commit/2d0fb41035cb8440094097a5ffc70ee383ee16e9))
* **deps:** bump gg.grounds:scene-format from 0.4.0 to 0.4.4 ([#98](https://github.com/groundsgg/service-maps/issues/98)) ([d02378e](https://github.com/groundsgg/service-maps/commit/d02378ecbb89d59ad580c3a41dd2984810a2bb5b))
* **deps:** preserve Kotlin compiler and daemon compatibility ([#93](https://github.com/groundsgg/service-maps/issues/93)) ([63e3e58](https://github.com/groundsgg/service-maps/commit/63e3e584a48fc3c3ecb3535304a98cb0e5c25fef))
* **light:** rebake pinned maps with vanilla transparency ([#101](https://github.com/groundsgg/service-maps/issues/101)) ([38c9098](https://github.com/groundsgg/service-maps/commit/38c90987c1db274e2150609ca9ee4e5f6cb4900d))

## [1.11.1](https://github.com/groundsgg/service-maps/compare/v1.11.0...v1.11.1) (2026-11-02)


### Bug Fixes

* **metrics:** expose Maps Prometheus metrics ([#91](https://github.com/groundsgg/service-maps/issues/91)) ([88a539e](https://github.com/groundsgg/service-maps/commit/88a539ef5f9cf496d323b2c811d7518f1bb9d033))

## [1.11.0](https://github.com/groundsgg/service-maps/compare/v1.10.2...v1.11.0) (2026-11-02)


### Features

* publish Native Maps API alongside JVM workers ([#89](https://github.com/groundsgg/service-maps/issues/89)) ([b76d43e](https://github.com/groundsgg/service-maps/commit/b76d43e0c60977e608e04122b825886474609796))

## [1.10.2](https://github.com/groundsgg/service-maps/compare/v1.10.1...v1.10.2) (2026-11-01)


### Bug Fixes

* **deps:** release the published NPC scene action catalogue ([#82](https://github.com/groundsgg/service-maps/issues/82)) ([cd1b8ab](https://github.com/groundsgg/service-maps/commit/cd1b8ab101eda15b319b25b599eef3a030ca1058))

## [1.10.1](https://github.com/groundsgg/service-maps/compare/v1.10.0...v1.10.1) (2026-10-23)


### Bug Fixes

* **pins:** connect to NATS at boot ([#79](https://github.com/groundsgg/service-maps/issues/79)) ([d397aec](https://github.com/groundsgg/service-maps/commit/d397aec9f734d608e30728ae8cab18f567d3cefb))

## [1.10.0](https://github.com/groundsgg/service-maps/compare/v1.9.0...v1.10.0) (2026-10-23)


### Features

* **pins:** announce pin file changes on NATS ([#77](https://github.com/groundsgg/service-maps/issues/77)) ([1fe9c31](https://github.com/groundsgg/service-maps/commit/1fe9c314681310755c45e78d12ae2da1aa4f2e1d))

## [1.9.0](https://github.com/groundsgg/service-maps/compare/v1.8.0...v1.9.0) (2026-10-23)


### Features

* keep buildserver working copies ([#75](https://github.com/groundsgg/service-maps/issues/75)) ([7e28104](https://github.com/groundsgg/service-maps/commit/7e281045a88e4df9b81f7bb8f60adb894e56798b))

## [1.8.0](https://github.com/groundsgg/service-maps/compare/v1.7.0...v1.8.0) (2026-10-19)


### Features

* **lit:** light every pinned bundle once and publish the lit copy ([#74](https://github.com/groundsgg/service-maps/issues/74)) ([d626433](https://github.com/groundsgg/service-maps/commit/d62643309c72ec05596657a2373dfd9fe9baa9b0))


### Bug Fixes

* **build:** resolve dependencies through the runner mirror in Docker builds ([#66](https://github.com/groundsgg/service-maps/issues/66)) ([eae2194](https://github.com/groundsgg/service-maps/commit/eae2194f2e5d03ee9087c165d3abfd88e886f852))

## [1.7.0](https://github.com/groundsgg/service-maps/compare/v1.6.0...v1.7.0) (2026-10-13)


### Features

* derive scenes with fractional NPC bounce distance ([#64](https://github.com/groundsgg/service-maps/issues/64)) ([76331b6](https://github.com/groundsgg/service-maps/commit/76331b61bd19ee84ff54837a2bae88566997b8cb))

## [1.6.0](https://github.com/groundsgg/service-maps/compare/v1.5.4...v1.6.0) (2026-10-13)


### Features

* derive scenes with optional NPC collision ([#62](https://github.com/groundsgg/service-maps/issues/62)) ([780ee70](https://github.com/groundsgg/service-maps/commit/780ee70a146c62e14846752e465261af000af506))

## [1.5.4](https://github.com/groundsgg/service-maps/compare/v1.5.3...v1.5.4) (2026-10-07)


### Bug Fixes

* read the 26.3 pack set with resourcepacks-client 1.0.0 ([#60](https://github.com/groundsgg/service-maps/issues/60)) ([0628e8c](https://github.com/groundsgg/service-maps/commit/0628e8cddf04fbe02b2e4f0716680315190d3863))

## [1.5.3](https://github.com/groundsgg/service-maps/compare/v1.5.2...v1.5.3) (2026-09-09)


### Bug Fixes

* resolve asset catalogs from packset artifacts ([#57](https://github.com/groundsgg/service-maps/issues/57)) ([19785f5](https://github.com/groundsgg/service-maps/commit/19785f573496a442adacbb057cf6d2f2cb042e11))

## [1.5.2](https://github.com/groundsgg/service-maps/compare/v1.5.1...v1.5.2) (2026-09-09)


### Bug Fixes

* use numeric runtime container user ([#55](https://github.com/groundsgg/service-maps/issues/55)) ([89c855a](https://github.com/groundsgg/service-maps/commit/89c855a16960e2ad00ba5fcfd99be89f4d95324b))

## [1.5.1](https://github.com/groundsgg/service-maps/compare/v1.5.0...v1.5.1) (2026-09-09)


### Bug Fixes

* run derive workers as numeric non-root user ([#53](https://github.com/groundsgg/service-maps/issues/53)) ([aa96186](https://github.com/groundsgg/service-maps/commit/aa9618634cc666434f963b3af1719d420e27900b))

## [1.5.0](https://github.com/groundsgg/service-maps/compare/v1.4.0...v1.5.0) (2026-09-02)


### Features

* resolve exact lobby action catalogs during map derivation ([#48](https://github.com/groundsgg/service-maps/issues/48)) ([46a3980](https://github.com/groundsgg/service-maps/commit/46a3980e6256ee48d6ca830b04d86cf01a7f60ff))

## [1.4.0](https://github.com/groundsgg/service-maps/compare/v1.3.0...v1.4.0) (2026-08-27)


### Features

* add asynchronous scene map derivation ([#43](https://github.com/groundsgg/service-maps/issues/43)) ([60f9497](https://github.com/groundsgg/service-maps/commit/60f9497da93983d3d9179eddb74659e9c60e9184))

## [1.3.0](https://github.com/groundsgg/service-maps/compare/v1.2.0...v1.3.0) (2026-08-02)


### Features

* publish an OpenAPI snapshot ([#20](https://github.com/groundsgg/service-maps/issues/20)) ([3423259](https://github.com/groundsgg/service-maps/commit/34232591258edcc4391a3afbfc1cf82234d52baa))

## [1.2.0](https://github.com/groundsgg/service-maps/compare/v1.1.2...v1.2.0) (2026-08-02)


### Features

* report where a map is live ([#18](https://github.com/groundsgg/service-maps/issues/18)) ([8f52c06](https://github.com/groundsgg/service-maps/commit/8f52c06817a0a868c298dfc6d5f9a8ddac615e62))

## [1.1.2](https://github.com/groundsgg/service-maps/compare/v1.1.1...v1.1.2) (2026-08-02)


### Bug Fixes

* publishing must promote the bundle into a public bucket ([#16](https://github.com/groundsgg/service-maps/issues/16)) ([ceae9e9](https://github.com/groundsgg/service-maps/commit/ceae9e90bfc4d384e17132a2cd4324bdff8e34ad))

## [1.1.1](https://github.com/groundsgg/service-maps/compare/v1.1.0...v1.1.1) (2026-08-01)


### Bug Fixes

* **chart:** allow the platform to own the ServiceAccount ([#14](https://github.com/groundsgg/service-maps/issues/14)) ([a310070](https://github.com/groundsgg/service-maps/commit/a310070f52c1e1d70c35c7feea4184cb5faf7e56))

## [1.1.0](https://github.com/groundsgg/service-maps/compare/v1.0.0...v1.1.0) (2026-07-30)


### Features

* serve creator content from its own bucket and host ([#10](https://github.com/groundsgg/service-maps/issues/10)) ([64cae63](https://github.com/groundsgg/service-maps/commit/64cae63ecd77f1199a4c1cfaec5f3cbe6156e26f))


### Bug Fixes

* first-party content is served from maps.grounds.gg ([#12](https://github.com/groundsgg/service-maps/issues/12)) ([9f71c69](https://github.com/groundsgg/service-maps/commit/9f71c69cea36e6713216de7d4df75e50110891ad))

## 1.0.0 (2026-07-30)


### Features

* map registry — schema, API, authorization and Helm chart ([#1](https://github.com/groundsgg/service-maps/issues/1)) ([d6dab58](https://github.com/groundsgg/service-maps/commit/d6dab582a83b1da63d35cd6372a994899cd14248))
