package gg.grounds.resourcepacks.catalog;
public final class GroundsAssetCatalog {
 public static final GroundsAssetCatalog INSTANCE=new GroundsAssetCatalog();
 public Object getCatalog() throws Exception {
  String base="gg.grounds.scene.format.";
  Class<?> marker=Class.forName("kotlin.jvm.internal.DefaultConstructorMarker");
  Class<?> range=Class.forName(base+"CatalogVersionRange");
  Object compatible=range.getConstructor(String.class,String.class,String.class,marker)
    .newInstance("grounds:resourcepacks","e1-contract","e1-contract",null);
  Class<?> kind=Class.forName(base+"AssetKind");
  Object npc=kind.getMethod("valueOf",String.class).invoke(null,"NPC_BODY");
  Object wave=Class.forName(base+"LocalId").getMethod("box-impl",String.class).invoke(null,"wave");
  Object key=Class.forName(base+"AssetKey").getMethod("box-impl",String.class).invoke(null,"grounds:mechanic");
  Object asset=Class.forName(base+"AssetDefinition")
    .getConstructor(String.class,kind,java.util.Set.class,Class.forName(base+"LocalBounds"),java.util.Map.class,marker)
    .newInstance("grounds:mechanic",npc,java.util.Set.of(wave),null,java.util.Map.of(),null);
  return Class.forName(base+"AssetCatalog").getConstructor(String.class,String.class,range,java.util.Map.class,marker)
    .newInstance("grounds:assets","e1-contract",compatible,java.util.Map.of(key,asset),null);
 }
}
