package com.aistorystudio.cast;

import com.aistorystudio.provider.StorageProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class CharacterCastStoreTest {

    @TempDir Path root;

    private StorageProvider storage() {
        return new StorageProvider() {
            public Path store(String r, byte[] c) {
                try { Path p = root.resolve(r); Files.createDirectories(p.getParent()); Files.write(p, c); return p; }
                catch (IOException e) { throw new UncheckedIOException(e); }
            }
            public Path resolve(String r) { return root.resolve(r); }
            public InputStream read(String r) { return null; }
            public boolean exists(String r) { return Files.exists(root.resolve(r)); }
            public void delete(String r) { }
        };
    }

    private CharacterCastStore withCast(StorageProvider sp) {
        CharacterCastStore s = new CharacterCastStore(sp);
        String[][] cast = {{"Asha", "Dadi"}, {"Ravi", ""}, {"Mr. Gupta", ""}};
        long t = 1;
        for (String[] c : cast) {
            CastCharacter x = new CastCharacter();
            x.id = c[0]; x.name = c[0]; x.createdAt = t++;
            if (!c[1].isEmpty()) x.aliases.add(c[1]);
            x.referencePath = "character-cast/" + c[0].replace(" ", "") + "/ref.png";
            sp.store(x.referencePath, new byte[]{1});
            x.referenceStatus = CastCharacter.ReferenceStatus.READY;
            s.save(x);
        }
        return s;
    }

    private static List<String> names(List<CharacterCastStore.Resolved> r) {
        return r.stream().map(x -> x.character().name).toList();
    }

    @Test
    void resolvesNamesAndAliasesInMentionOrderCappedAtTwo() {
        CharacterCastStore s = withCast(storage());
        assertEquals(List.of("Ravi", "Asha"), names(s.resolve("Ravi runs to Dadi in the market.")));
        assertEquals(List.of("Ravi"), names(s.resolve("Asharani is someone else; RAVI waves.")));
        assertEquals(List.of("Mr. Gupta", "Asha"), names(s.resolve("Mr. Gupta, Asha and Ravi argue.")));
        assertEquals(List.of(), names(s.resolve("Nobody is named here.")));
    }

    @Test
    void skipsDisabledAndNotReadyCharacters() {
        StorageProvider sp = storage();
        CharacterCastStore s = withCast(sp);
        CastCharacter asha = s.get("Asha").orElseThrow();
        asha.enabled = false;
        s.save(asha);
        CastCharacter ravi = s.get("Ravi").orElseThrow();
        ravi.referenceStatus = CastCharacter.ReferenceStatus.GENERATING;
        s.save(ravi);
        assertEquals(List.of(), names(s.resolve("Asha and Ravi")));
    }

    @Test
    void persistsAcrossInstances() {
        StorageProvider sp = storage();
        withCast(sp);
        CharacterCastStore again = new CharacterCastStore(sp);
        assertEquals(3, again.list().size());
        assertEquals(List.of("Dadi"), again.findByName("asha").orElseThrow().aliases);
    }

    @Test
    void pinnedCharacterFillsFreeSlotsEvenWhenNotNamed() {
        CharacterCastStore s = withCast(storage());
        CastCharacter asha = s.get("Asha").orElseThrow();
        asha.alwaysInClip = true;
        s.save(asha);
        assertEquals(List.of("Asha"), names(s.resolve("The toddler laughs at the tractor.")));
        assertEquals(List.of("Ravi", "Asha"), names(s.resolve("Ravi waves.")));
        assertEquals(List.of("Ravi", "Mr. Gupta"), names(s.resolve("Ravi and Mr. Gupta wave.")));
    }
}
