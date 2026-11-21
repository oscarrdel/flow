package com.rainyworks.flow.core.model

/** Lightweight row shown on the home screen. */
data class ProjectSummary(
    val id: String,
    val name: String,
    val updatedAtMs: Long,
    val durationUs: Long,
    val thumbnailPath: String?,
    val sizeBytes: Long,
)
