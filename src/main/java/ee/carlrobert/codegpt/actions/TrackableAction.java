package ee.carlrobert.codegpt.actions;

import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import javax.swing.Icon;
import org.jetbrains.annotations.NotNull;

public abstract class TrackableAction extends AnAction {

  public TrackableAction(
      String text,
      String description,
      Icon icon) {
    super(text, description, icon);
  }

  public abstract void handleAction(@NotNull AnActionEvent e);

  @Override
  public void actionPerformed(@NotNull AnActionEvent e) {
    handleAction(e);
  }
}