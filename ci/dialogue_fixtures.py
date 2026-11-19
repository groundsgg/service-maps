"""Deterministic authored Story and trusted profile/catalog fixtures, never a product dependency."""
import hashlib
import json
import pathlib
import subprocess
import zipfile


def encoded(value):
    return (json.dumps(value, sort_keys=True, separators=(',', ':')) + '\n').encode()


def prepare(directory):
    # Reflection only bridges Kotlin value-class constructors in this isolated generated catalog.
    # The JAR uses the actual scene-format classes supplied by the worker under qualification.
    source = directory / 'catalog-source/gg/grounds/resourcepacks/catalog/GroundsAssetCatalog.java'
    source.parent.mkdir(parents=True, exist_ok=True)
    source.write_bytes((pathlib.Path(__file__).resolve().parent / 'fixtures/GroundsAssetCatalog.java').read_bytes())
    classes = directory / 'catalog-classes'
    subprocess.run(['javac', '--release', '21', '-d', str(classes), str(source)], check=True, capture_output=True)
    jar = directory / 'dialogue-catalog.jar'
    with zipfile.ZipFile(jar, 'w') as archive:
        for path in sorted(classes.rglob('*.class')):
            entry = zipfile.ZipInfo(str(path.relative_to(classes)), (1980, 1, 1, 0, 0, 0))
            entry.external_attr = 0o100644 << 16
            archive.writestr(entry, path.read_bytes())
    jar_bytes = jar.read_bytes()
    jar_sha = hashlib.sha256(jar_bytes).hexdigest()
    catalog = dict(channel='pinned', id='grounds:resourcepacks', version='e1-contract',
                   coordinate='gg.grounds:resourcepacks-catalog:e1-contract', file='catalog.jar',
                   sha256=jar_sha, size=len(jar_bytes),
                   uri=f'https://maps-fixtures/resourcepacks/catalog-pins/contract/e1-contract/{jar_sha}/catalog.jar')
    root = pathlib.Path(__file__).resolve().parent.parent / 'src/test/resources/derive/dialogue'
    authored = {str(path.relative_to(root)): path.read_bytes() for path in root.rglob('*.json')}
    grounds = root.parent / 'dialogue-grounds'
    profile = (grounds / 'profile.json').read_bytes()
    (directory / 'dialogue-profile.json').write_bytes(profile)
    sha = hashlib.sha256(profile).hexdigest()
    pin = dict(id='grounds:dialogue-contract', version='1', sha256=sha, sizeBytes=len(profile),
               uri=f'https://maps-fixtures/dialogue/profiles/contract/1/{sha}/profile.json')
    authored['grounds/dialogue.json'] = (grounds / 'bindings.json').read_bytes()
    authored['scene.json'] = (grounds / 'scene.json').read_bytes()
    return pin, catalog, authored
