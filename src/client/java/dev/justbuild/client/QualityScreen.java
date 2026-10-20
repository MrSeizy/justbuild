package dev.justbuild.client;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;

/** Inspect a warning's location, then request a reversible repair preview without calling the AI. */
final class QualityScreen extends ToolScreen {
    private int page;
    QualityScreen(Screen parent) { super("Build quality checks", parent); }
    @Override protected void build() {
        var issues = ClientState.qualityIssues(); page = Math.clamp(page, 0, Math.max(0, issues.size()-1));
        int half=(panelWidth-4)/2, third=(panelWidth-8)/3;
        if(!issues.isEmpty()) {
            var issue=issues.get(page);
            button("Highlight location",left,height-80,half,()->{ QualityOutline.highlight(issue.id()); minecraft.setScreen(null); }).active=issue.position()!=null;
            button("Preview repair",left+half+4,height-80,half,()->run("quality repair "+issue.id())).active=!issue.repair().isEmpty();
        }
        button("Revert repair suggestion",left,height-56,panelWidth,()->run("quality revert")).active=ClientState.flag("qualityUndo");
        button("Back",left,height-28,third,this::onClose);
        button("Previous",left+third+4,height-28,third,()->{page--;rebuildWidgets();}).active=page>0;
        button("Next",left+2*(third+4),height-28,third,()->{page++;rebuildWidgets();}).active=page+1<issues.size();
    }
    @Override public void extractRenderState(GuiGraphicsExtractor g,int x,int y,float tick) {
        super.extractRenderState(g,x,y,tick);
        g.text(font,"Advisory checks; decoration can be intentional.",left,30,Glass.MUTED);
        var issues=ClientState.qualityIssues();
        if(issues.isEmpty()) {
            text(g,ClientState.preview()==null?"No waiting preview.":"No common doorway, landing, support, or foundation issues detected.",52,height-92,Glass.TEXT); return;
        }
        var issue=issues.get(Math.min(page,issues.size()-1));
        int next=text(g,(page+1)+" / "+issues.size()+" · "+issue.message(),52,height-142,Glass.TEXT);
        text(g,"Suggestion: "+issue.repair()+" Confirm with Build it after checking the preview.",Math.max(next+10,height-132),height-88,Glass.MUTED);
    }
}
