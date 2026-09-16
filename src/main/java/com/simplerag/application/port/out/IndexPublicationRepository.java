package com.simplerag.application.port.out;

import com.simplerag.search.IndexManifest;

import java.util.Optional;

public interface IndexPublicationRepository {
    boolean beginIndexBuild(String knowledgeBaseId, long revision);
    default Long beginIndexBuildRevision(String knowledgeBaseId, long expectedRevision) {
        return beginIndexBuild(knowledgeBaseId, expectedRevision) ? expectedRevision : null;
    }
    void markIndexBuildFailed(String knowledgeBaseId, long revision, String error);
    void markIndexIncompatible(String knowledgeBaseId, String error);
    void markIndexDirty(String knowledgeBaseId, String error);
    boolean markIndexBuildDiscarded(String knowledgeBaseId, long revision, String error);
    boolean publishIndex(IndexManifest manifest, String fileName);
    Optional<String> findIndexFile(String knowledgeBaseId, long revision);
}
