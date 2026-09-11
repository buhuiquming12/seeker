package com.simplerag.application.port.in;

import com.simplerag.application.dto.WorkspaceLayout;

/** Remembers how the window was arranged, so relaunching does not undo the arrangement. */
public interface ManageWorkspaceLayout {
    WorkspaceLayout workspaceLayout();

    void saveWorkspaceLayout(WorkspaceLayout layout);
}
