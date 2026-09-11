package com.simplerag.application.dto;

import java.nio.file.Path;

/**
 * State of the local embedding model on disk, as the settings page needs to describe it.
 *
 * @param installed whether every file the model needs is present
 * @param directory where the files live, shown so a manual install can be checked
 * @param bytes size of the files that are present; {@code 0} when nothing is installed
 * @param modelName identifier the index records, so a mismatch is recognizable
 */
public record LocalModelView(boolean installed, Path directory, long bytes, String modelName) {
}
