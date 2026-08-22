package gg.grounds.resourcepacks.catalog;

public final class CatalogSupport {
  private CatalogSupport() {}

  public static Object catalog() {
    return gg.grounds.catalog.CatalogJarLoaderFixtures.catalog();
  }
}
