package dev.doc2tex;

import static org.junit.jupiter.api.Assertions.*;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpStatus;

class BibliographyVersionTest {
    @TempDir Path directory;
    private static final String SOURCE = "\\begin{document}\nSYNTHETIC_VERSION_BODY\n\\end{document}";
    private AppProperties properties() { return new AppProperties(directory.toString(), "", "", 1, 1, 10, 10); }
    private JobStore store(SourceVersionStore versions) throws IOException { var store = new JobStore(properties(), versions); store.load(); return store; }
    private Job create(JobStore store) throws IOException {
        Job job = new Job(UUID.randomUUID().toString(), "bib-version.docx", "article", "COMPLETED", "NOT_REQUESTED", "", "", Instant.now(), Instant.now(), 100, 0);
        store.save(job); Path project = store.directory(job.id()); Files.createDirectories(project.resolve("output"));
        Files.writeString(project.resolve("output/main.tex"), SOURCE); Files.write(project.resolve("input.docx"), SampleDocument.create());
        store.versions().initialize(job, project, false); return job;
    }
    private BibliographyAssets assets(String text) { return new BibliographyAssets("synthetic.bib", text.getBytes(StandardCharsets.UTF_8), new byte[]{1, 2, 3}, new byte[]{4, 5}); }
    private String text(byte[] bytes) { return new String(bytes, StandardCharsets.UTF_8); }

    @Test void sourceSavesRestoreAndRestartKeepTheirMatchingBibliographyAndFontAssets() throws Exception {
        var versions = new SourceVersionStore(); var store = store(versions); Job job = create(store); Path project = store.directory(job.id());
        var original = assets("@book{first,title={Résumé}}\n");
        job = versions.commit(job, project, SOURCE, -1, store::save, original);
        assertEquals(1, job.revision()); assertArrayEquals(original.bibliography(), versions.bibliography(job, project, 1).bibliography());
        job = versions.commit(job, project, SOURCE + "\n% EDIT", -1, store::save);
        assertArrayEquals(original.bibliography(), versions.bibliography(job, project, 2).bibliography());
        assertEquals(original.totalBytes(), versions.list(job, project).items().getFirst().assetBytes());
        assertEquals(SOURCE.getBytes(StandardCharsets.UTF_8).length * 3 + "\n% EDIT".length() + original.totalBytes() * 2, versions.list(job, project).bytesUsed());
        job = versions.commit(job, project, text(versions.readVersion(job, project, 0)), 0, store::save);
        assertNull(versions.bibliography(job, project, job.revision())); assertFalse(Files.exists(project.resolve("output/bibliography/references.bib")));
        Path interruptedCache = project.resolve("output/bibliography/references.bib.version-tmp"); Files.writeString(interruptedCache, "INCOMPLETE_CACHE");
        versions.recover(job, project); assertFalse(Files.exists(interruptedCache));
        job = versions.commit(job, project, text(versions.readVersion(job, project, 1)), 1, store::save);
        assertArrayEquals(original.font(), Files.readAllBytes(project.resolve("output/bibliography/NotoSerif-Regular.ttf")));
        Files.writeString(project.resolve("output/bibliography/references.bib"), "UNCOMMITTED CACHE");
        var restarted = store(new SourceVersionStore()); Job restored = restarted.get(job.id());
        assertEquals(SOURCE, text(restarted.versions().current(restored, project)));
        assertArrayEquals(original.bibliography(), Files.readAllBytes(project.resolve("output/bibliography/references.bib")));
        assertEquals("synthetic.bib", restarted.versions().list(restored, project).items().getFirst().bibliographyFilename());
    }

    @ParameterizedTest @EnumSource(SourceVersionStore.Stage.class)
    void interruptedAssetCommitsRecoverTheSingleCommittedSourceAssetPair(SourceVersionStore.Stage stage) throws Exception {
        class Interrupted extends SourceVersionStore {
            boolean armed;
            @Override void checkpoint(Stage current) throws IOException { if (armed && current == stage) throw new IOException("Synthetic interruption"); }
        }
        var versions = new Interrupted(); var store = store(versions); Job job = create(store); Path project = store.directory(job.id());
        job = versions.commit(job, project, SOURCE, -1, store::save, assets("ORIGINAL_BIB"));
        var replacement = new BibliographyAssets("synthetic.bib", "NEW_BIB".getBytes(StandardCharsets.UTF_8), new byte[]{9, 8, 7}, new byte[]{6});
        Job prior = job; versions.armed = true;
        assertThrows(IOException.class, () -> versions.commit(prior, project, SOURCE + "\n% NEW", -1, store::save, replacement));
        var restarted = store(new SourceVersionStore()); Job committed = restarted.get(job.id());
        boolean advanced = stage == SourceVersionStore.Stage.METADATA_COMMITTED;
        assertEquals(advanced ? 2 : 1, committed.revision());
        assertEquals(advanced ? SOURCE + "\n% NEW" : SOURCE, text(restarted.versions().current(committed, project)));
        assertEquals(advanced ? "NEW_BIB" : "ORIGINAL_BIB", Files.readString(project.resolve("output/bibliography/references.bib")));
        assertEquals(advanced ? "NEW_BIB" : "ORIGINAL_BIB", text(restarted.versions().bibliography(committed, project, committed.revision()).bibliography()));
        assertArrayEquals(advanced ? replacement.font() : new byte[]{1, 2, 3}, Files.readAllBytes(project.resolve("output/bibliography/NotoSerif-Regular.ttf")));
        assertArrayEquals(advanced ? replacement.license() : new byte[]{4, 5}, Files.readAllBytes(project.resolve("output/bibliography/OFL-Noto.txt")));
        assertEquals(advanced, Files.exists(project.resolve(".versions/2")));
    }

    @ParameterizedTest @ValueSource(strings = {"references.bib", "NotoSerif-Regular.ttf", "OFL-Noto.txt"})
    void corruptCommittedAssetsCannotBeExportedOrUsedForRestore(String filename) throws Exception {
        var versions = new SourceVersionStore(); var store = store(versions); Job job = create(store); Path project = store.directory(job.id());
        job = versions.commit(job, project, SOURCE, -1, store::save, assets("PRESERVE_BIB")); Job committed = job;
        Path asset = project.resolve(".versions/1/bibliography").resolve(filename); byte[] bytes = Files.readAllBytes(asset); bytes[0] ^= 1; Files.write(asset, bytes);
        assertThrows(IOException.class, () -> versions.current(committed, project));
        assertThrows(IOException.class, () -> versions.commit(committed, project, SOURCE, 0, store::save));
        var restarted = store(new SourceVersionStore()); assertEquals(1, restarted.list().size());
        assertEquals(HttpStatus.CONFLICT, assertThrows(ApiException.class, () -> restarted.requireHistory(committed.id())).status);
        assertTrue(Files.isRegularFile(project.resolve("input.docx"))); assertEquals(1, restarted.get(committed.id()).revision());
    }

    @Test void legacyUnmanagedFilesArePreservedAndAssetBytesCountTowardHistoryLimit() throws Exception {
        var versions = new SourceVersionStore(); var store = store(versions); Job job = create(store); Path project = store.directory(job.id());
        Path cache = project.resolve("output/bibliography/references.bib"); Files.createDirectories(cache.getParent()); Files.writeString(cache, "UNMANAGED_USER_ASSET");
        versions.recover(job, project); assertEquals("UNMANAGED_USER_ASSET", Files.readString(cache));
        assertThrows(IOException.class, () -> versions.commit(job, project, SOURCE, -1, store::save, assets("IMPORT_BIB")));
        var restarted = store(new SourceVersionStore()); assertEquals(0, restarted.get(job.id()).revision()); assertEquals("UNMANAGED_USER_ASSET", Files.readString(cache));
        assertFalse(Files.exists(project.resolve(".versions/1")));
        var limited = new SourceVersionStore(100, SOURCE.getBytes(StandardCharsets.UTF_8).length * 2L + assets("IMPORT_BIB").totalBytes() - 1);
        assertEquals(HttpStatus.INSUFFICIENT_STORAGE, assertThrows(ApiException.class, () -> limited.commit(job, project, SOURCE, -1, restarted::save, assets("IMPORT_BIB"))).status);
        assertEquals("UNMANAGED_USER_ASSET", Files.readString(cache));
    }

    @Test void replacingBibliographyRetainsCapturedProjectFontsInsteadOfDependingOnCurrentToolInstallation() throws Exception {
        var original = assets("ORIGINAL_BIB"); var replacement = BibliographyAssets.replace("replacement.bib", "NEW_BIB".getBytes(StandardCharsets.UTF_8), original);
        assertEquals("replacement.bib", replacement.filename()); assertEquals("NEW_BIB", text(replacement.bibliography()));
        assertArrayEquals(original.font(), replacement.font()); assertArrayEquals(original.license(), replacement.license());
    }
}
