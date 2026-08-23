package com.druk.lmplayground.storage

import android.util.Log

/**
 * Deletes model files that a catalog change has retired.
 *
 * Distinct from [LegacyDownloadCleanup], which sweeps app-private scratch
 * directories: this operates on the user's chosen model folder, where files are
 * theirs and deleting the wrong one costs them a multi-GB re-download.
 *
 * So every removal is **gated on its replacement already being present**. A
 * user who never downloads the replacement keeps a working (if slower) setup;
 * nobody is left with a vision model and no projector because they happened to
 * be offline when they updated the app.
 */
object SupersededFileCleanup {

    private const val TAG = "SupersededFileCleanup"

    /**
     * retired filename -> the file that must exist before it is deleted.
     *
     * Gemma 4's projector shipped as BF16, which has no optimised ARM CPU
     * kernel: on devices where the CLIP encoder falls back to CPU it took ~50s
     * per image (measured on desktop; worse on a phone) versus ~3s for the
     * Q8_0 build of the same projector, which is also 400 MB smaller. Switching
     * the catalog to Q8_0 orphans the BF16 file.
     */
    private val SUPERSEDED = mapOf(
        "mmproj-gemma-4-E2B-it-BF16.gguf" to "mmproj-gemma-4-E2B-it-Q8_0.gguf",
        "mmproj-gemma-4-E4B-it-BF16.gguf" to "mmproj-gemma-4-E4B-it-Q8_0.gguf",
    )

    /**
     * Delete retired files whose replacement is already downloaded.
     *
     * Must run off the main thread — it does SAF I/O.
     *
     * @return total bytes reclaimed.
     */
    fun run(repository: StorageRepository): Long {
        val present = try {
            repository.getModelFiles().associate { it.name to it.sizeBytes }
        } catch (e: Exception) {
            // A revoked or unavailable SAF grant is normal (folder on removed
            // storage, permission reset). Nothing to clean; try again next start.
            Log.d(TAG, "model folder unavailable, skipping cleanup: ${e.message}")
            return 0L
        }

        var reclaimed = 0L
        for (retired in selectDeletable(present.keys)) {
            val size = present[retired] ?: 0L
            if (repository.deleteModel(retired)) {
                reclaimed += size
                Log.i(TAG, "deleted superseded $retired (${size / 1_000_000} MB), " +
                    "replaced by ${SUPERSEDED[retired]}")
            }
        }
        return reclaimed
    }

    /**
     * Which retired files are safe to delete given what is [present].
     *
     * Pure, so the gating rule — never delete before the replacement has
     * landed — is unit-testable without a Context or a SAF folder.
     */
    internal fun selectDeletable(present: Set<String>): List<String> =
        SUPERSEDED.filter { (retired, replacement) ->
            retired in present && replacement in present
        }.keys.toList()

    /** Retired filenames, so the storage screen doesn't flag them as unknown. */
    fun supersededFilenames(): Set<String> = SUPERSEDED.keys
}
