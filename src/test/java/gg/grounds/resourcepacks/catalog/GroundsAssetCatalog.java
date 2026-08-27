package gg.grounds.resourcepacks.catalog;

public final class GroundsAssetCatalog {
  public static final GroundsAssetCatalog INSTANCE = new GroundsAssetCatalog();

  public Object getCatalog() {
    return gg.grounds.catalog.CatalogJarLoaderFixtures.catalog();
  }
}
