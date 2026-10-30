#!/usr/bin/env python3
"""Make a self-contained comparison report from one or more QualityGalleryGameTest runs."""
import argparse
import base64
import json
from pathlib import Path


def read_run(directory: Path) -> dict:
    root = directory.resolve()
    data = json.loads((root / "results.json").read_text())
    if data.get("format") != 1 or not isinstance(data.get("cases"), list):
        raise ValueError(f"Unsupported gallery results: {root}")
    for case in data["cases"]:
        # The first draft's checks as the reviewer received them, when the run wrote them.
        evidence = root / f"{case.get('id', '')}-evidence.txt"
        if case.get("id") and evidence.resolve().parent == root and evidence.is_file():
            case["evidence"] = evidence.read_text()[:20000]
        for stage in (case, case.get("draft", {})):
            images = [(stage, key) for key in ("before", "after", "detailImage") if key in stage]
            images.extend((view, "image") for view in stage.get("views", []))
            for owner, key in images:
                image = (root / owner[key]).resolve()
                if not image.is_relative_to(root) or image.suffix.lower() != ".png":
                    raise ValueError("Gallery images must be PNGs inside their run directory")
                content = image.read_bytes()
                if not content.startswith(b"\x89PNG\r\n\x1a\n"):
                    raise ValueError(f"Not a PNG: {image.name}")
                owner[key] = "data:image/png;base64," + base64.b64encode(content).decode("ascii")
    return data


HTML = r'''<!doctype html>
<html lang="en"><meta charset="utf-8"><meta name="viewport" content="width=device-width, initial-scale=1">
<title>JustBuild quality gallery</title>
<style>
:root{color-scheme:dark;font:16px/1.5 system-ui,sans-serif;background:#10151b;color:#e7edf3}*{box-sizing:border-box}body{margin:0}header{padding:42px max(24px,5vw) 24px;background:linear-gradient(120deg,#202d3a,#151c25);border-bottom:1px solid #354456}h1{font-size:clamp(30px,4vw,52px);line-height:1.08;margin:8px 0 18px;letter-spacing:-.03em}h2{margin:28px 0 14px;text-transform:capitalize}p{max-width:84ch;color:#b5c4d3}small,.muted{color:#91a3b5}.eyebrow{color:#f6c16b;letter-spacing:.13em;text-transform:uppercase;font-size:12px;font-weight:700}nav{display:flex;gap:8px;flex-wrap:wrap;margin-top:24px}button,select{font:inherit;background:#22303f;border:1px solid #435569;border-radius:7px;padding:7px 12px;color:#e7edf3;cursor:pointer}button:hover,button[aria-pressed=true]{border-color:#f6c16b;background:#3c3426}main{padding:16px max(24px,5vw) 50px}.summary,.comparisons{display:grid;grid-template-columns:repeat(auto-fit,minmax(min(100%,360px),1fr));gap:18px}.run,.card{background:#1a232d;border:1px solid #354456;border-radius:12px;overflow:hidden}.run{padding:20px}.run b{font-size:22px}.tag{font-size:12px;border:1px solid #61758a;border-radius:99px;padding:3px 9px;display:inline-block;margin:4px 5px 6px 0}.pass{color:#9be0b3;border-color:#498d64}.fail{color:#ffb1a7;border-color:#a55048}.card-head,.info{padding:16px 20px}.card-head{display:flex;align-items:center;justify-content:space-between;gap:12px}.card img{display:block;width:100%;aspect-ratio:854/480;object-fit:contain;background:#080c10}figure{margin:0}figcaption{padding:8px 20px;color:#a9b9c9;font-size:13px}.views{display:flex;gap:6px;padding:0 20px 12px;flex-wrap:wrap}.check{margin:10px 0;padding-left:16px;border-left:3px solid #498d64}.check.bad{border-color:#a55048}.check p{font-size:14px;margin:2px 0 0}details{margin:14px 0}summary{cursor:pointer;color:#f6c16b}code{font:12px ui-monospace,monospace;overflow-wrap:anywhere;color:#adc5dc}.request{padding:14px;border:1px solid #354456;background:#151e28;border-radius:8px;font-size:14px;white-space:pre-wrap}.empty{padding:24px}.notice{border-left:3px solid #f6c16b;padding:8px 15px;margin:18px 0}.hidden{display:none}a{color:#f6c16b}
</style>
<header><div class="eyebrow">JustBuild · Evaluation workspace</div><h1>Build quality, side by side.</h1>
<p>Fixed scenes for entrances, usable rooms, preservation, materials and limits. Screenshots show what was actually placed in Minecraft. Automated checks cover functional criteria; use the images to judge proportions, detail and style.</p>
<div class="notice">Deterministic fixtures validate the harness and placement checks. Live-provider and imported runs are labeled separately. Passing fixtures alone says nothing about model quality.</div>
<nav id="nav"></nav></header><main><div class="summary" id="summary"></div><div id="cases"></div></main>
<script id="data" type="application/json">__DATA__</script>
<script>
const runs=JSON.parse(document.getElementById('data').textContent);
const el=(tag,text,cls)=>{const n=document.createElement(tag);if(text!==undefined)n.textContent=text;if(cls)n.className=cls;return n};
const short=s=>(s||'unknown').slice(0,12);
for(const run of runs){
  const box=el('article',undefined,'run');box.append(el('div',run.mode,'eyebrow'),el('b',run.label));
  const passed=run.cases.filter(c=>c.passed).length;
  box.append(el('p',`${passed}/${run.cases.length} final cases passed · ${run.provider} · ${run.model}`));
  const drafts=run.cases.filter(c=>c.draft);
  if(drafts.length)box.append(el('p',`${drafts.filter(c=>c.draft.passed).length}/${drafts.length} first drafts passed`));
  box.append(el('small',`${run.createdAt} · ${run.revision} · Minecraft ${run.minecraft}`));
  const meta=el('p');meta.append(el('span',`${run.detail} · ${run.reviewRounds} scheduled review rounds`,'tag'),...(run.reviewMode?[el('span',`Review: ${run.reviewMode}`,'tag')]:[]),el('span',`Guides ${short(run.guideHash)}`,'tag'));
  if(run.timeoutSeconds&&run.timeoutMode!=='none')meta.append(el('span',`${run.timeoutSeconds / 60} min ${run.timeoutMode==='idle'?'without CLI output':'request limit'}`,'tag'));
  if(run.repeats)meta.append(el('span',`${run.repeats} attempts per scene`,'tag'));
  box.append(meta);document.getElementById('summary').append(box)
}
const caseKey=c=>`${c.scenario??c.id}-${c.repeat??1}`;
const names=[...new Set(runs.flatMap(r=>r.cases.map(caseKey)))];
const checks=(parent,rows)=>{for(const check of rows){const box=el('div',undefined,'check'+(check.passed?'':' bad'));box.append(el('strong',(check.passed?'✓ ':'× ')+check.name),el('p',check.detail));parent.append(box)}};
for(const name of names){
  const jump=el('button',name);jump.onclick=()=>document.getElementById('case-'+name).scrollIntoView({behavior:'smooth'});document.getElementById('nav').append(jump);
  const section=el('section');section.id='case-'+name;section.append(el('h2',name));
  const grid=el('div',undefined,'comparisons');section.append(grid);
  for(const run of runs){
    const c=run.cases.find(c=>caseKey(c)===name);const card=el('article',undefined,'card');grid.append(card);
    if(!c){card.append(el('p',`${run.label}: case not present`,'empty'));continue}
    const head=el('div',undefined,'card-head');head.append(el('strong',run.label),el('span',c.passed?'PASS':'NEEDS ATTENTION','tag '+(c.passed?'pass':'fail')));card.append(head);
    const fig=el('figure'),img=el('img'),caption=el('figcaption','Final · placed result');img.src=c.after;img.alt=name+' final placement';fig.append(img,caption);card.append(fig);
    const views=el('div',undefined,'views');
    for(const [stage,key,label] of [[c,'before','Source'],[c.draft,'after','First draft'],[c.draft,'detailImage','Draft inside'],[c,'after','Final'],[c,'detailImage','Final inside']]){
      if(!stage?.[key])continue;
      const b=el('button',label);b.setAttribute('aria-pressed',String(stage===c&&key==='after'));
      b.onclick=()=>{img.src=stage[key];img.alt=name+' '+label;caption.textContent=label+(key==='after'?' · placed result':'');for(const btn of views.children)btn.setAttribute('aria-pressed',String(btn===b))};views.append(b)
    }
    for(const [stage,prefix] of [[c.draft,'Draft'],[c,'Final']]) for(const view of stage?.views||[]){
      const label=prefix+' · '+view.label,b=el('button',label);b.setAttribute('aria-pressed','false');
      b.onclick=()=>{img.src=view.image;img.alt=name+' '+label;caption.textContent=label;for(const btn of views.children)btn.setAttribute('aria-pressed',String(btn===b))};views.append(b)
    }
    card.append(views);
    const info=el('div',undefined,'info');info.append(el('small',`${c.changedCells??'—'} changed cells · ${c.protectedCellsKept??0} protected cells kept`));
    if(c.guidesOffered!==undefined){const chosen=c.selectedGuides||[];info.append(el('p',chosen.length?`AI-selected guides: ${chosen.join(', ')} · ${(c.guideSelectionMillis/1000).toFixed(1)} s`:(c.guidesOffered?'Guide menu offered; no selection recorded.':'Guide selection disabled for this run.')))}
    if(c.planningMillis!==undefined){const reviewMillis=(c.reviews||[]).reduce((sum,r)=>sum+(r.elapsedMillis||0),0);info.append(el('p',`Planning ${(c.planningMillis/1000).toFixed(1)} s · Review ${(reviewMillis/1000).toFixed(1)} s`))}
    if(c.reviews?.length){const d=el('details');d.append(el('summary',`${c.reviews.length} review calls`));for(const r of c.reviews){d.append(el('p',`Round ${r.round} ${r.stage||''}: ${r.status} · ${r.mode}${r.providerMillis!==undefined?` · ${(r.providerMillis/1000).toFixed(1)} s`:''}`));if(r.error)d.append(el('p',r.error))}info.append(d)}
    if(c.evidence){const d=el('details');d.append(el('summary','Review evidence (the first draft\'s checks)'));d.append(el('div',c.evidence.trim(),'request'));info.append(d)}
    if(c.reviewRounds?.length){const d=el('details');d.append(el('summary',`${c.reviewRounds.length} review rounds`));for(const r of c.reviewRounds){d.append(el('p',`Round ${r.number??r.round}: ${r.repaired?(r.outcome||'repair'):'no repair'} · ${run.reviewMode==='self'?'self-review':'critique '+(r.critiqueParsed?'parsed':'not parsed')}`));const k=r.critique;if(k){d.append(el('p',`Reviewer verdict: ${k.verdict}`,'muted'));for(const p of k.problems||[])d.append(el('p',`${p.severity} · ${p.category}: ${p.problem} Fix: ${p.fix}`,'check'+(p.severity==='minor'?'':' bad')));if(k.keep?.length)d.append(el('p','Keep: '+k.keep.join('; '),'muted'))}}info.append(d)}
    if(c.draft){const d=el('details');d.append(el('summary','First-draft checks'));checks(d,c.draft.checks);info.append(d)}
    checks(info,c.checks);
    const details=el('details');details.append(el('summary','Request and provenance'),el('p',c.request,'request'));
    for(const [title,val] of [['Initial prompt',c.promptHash],['Plan prompt',c.planPromptHash||c.promptHash],['Plan',c.planHash],['Source scene',c.sourceHash],['Harness',run.harnessHash]]){details.append(el('div',title),el('code',val||'not available'))}
    details.append(el('p','Raw plans, responses, exact prompts, review evidence and PNG files are stored alongside results.json in this run directory.'));info.append(details);
    if(c.advisories?.length){const d=el('details');d.append(el('summary',`${c.advisories.length} advisory notes`));for(const text of c.advisories)d.append(el('p',text));info.append(d)}card.append(info)
  }
  document.getElementById('cases').append(section)
}
</script></html>'''


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("runs", nargs="+", type=Path, help="Directories containing results.json and PNGs")
    parser.add_argument("--output", required=True, type=Path, help="Self-contained HTML report")
    args = parser.parse_args()
    runs = [read_run(directory) for directory in args.runs]
    # Escape script terminators even if a hand-edited result contains HTML.
    payload = json.dumps(runs, ensure_ascii=True).replace("<", "\\u003c").replace(">", "\\u003e").replace("&", "\\u0026")
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(HTML.replace("__DATA__", payload), encoding="utf-8")
    print(args.output.resolve())


if __name__ == "__main__":
    main()
