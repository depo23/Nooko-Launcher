// Renders promo.html frames via Chromium (Brave) DevTools, then ffmpeg -> mp4 + gif.
import {spawn,execSync} from "node:child_process";import fs from "node:fs";import path from "node:path";
const out=process.argv[2];const BR="/Applications/Brave Browser.app/Contents/MacOS/Brave Browser";
const html="file://"+path.resolve(import.meta.dirname,"promo.html");
const prof=fs.mkdtempSync("/tmp/promo-prof-");
const p=spawn(BR,["--headless=new","--remote-debugging-port=9333","--user-data-dir="+prof,"--window-size=1280,720","--hide-scrollbars","--force-device-scale-factor=1","--no-first-run",html+"?s=cards&t=0"],{stdio:"ignore"});
let list;for(let i=0;i<40;i++){await new Promise(r=>setTimeout(r,500));try{list=await (await fetch("http://127.0.0.1:9333/json")).json();if(list.find(x=>x.type==="page"))break}catch{}}
const ws=new WebSocket(list.find(x=>x.type==="page").webSocketDebuggerUrl);
await new Promise(r=>ws.onopen=r);
let id=0;const pend={};ws.onmessage=e=>{const m=JSON.parse(e.data);if(m.id&&pend[m.id])pend[m.id](m.result)};
const send=(method,params={})=>new Promise(r=>{const i=++id;pend[i]=r;ws.send(JSON.stringify({id:i,method,params}))});
await send("Emulation.setDeviceMetricsOverride",{width:1280,height:720,deviceScaleFactor:1,mobile:false});
const FPS=30;
for(const [name,dur] of [["cards",12],["full",13]]){
 const dir=path.join(out,"frames-"+name);fs.rmSync(dir,{recursive:true,force:true});fs.mkdirSync(dir,{recursive:true});
 const n=dur*FPS;
 for(let i=0;i<n;i++){
  await send("Runtime.evaluate",{expression:`scene("${name}",${i/FPS})`});
  const r=await send("Page.captureScreenshot",{format:"png"});
  fs.writeFileSync(path.join(dir,String(i).padStart(4,"0")+".png"),Buffer.from(r.data,"base64"));
 }
 execSync(`ffmpeg -y -loglevel error -framerate ${FPS} -i "${dir}/%04d.png" -c:v libx264 -pix_fmt yuv420p -crf 16 "${out}/nooko-${name}.mp4"`);
 execSync(`ffmpeg -y -loglevel error -i "${out}/nooko-${name}.mp4" -vf "fps=15,scale=800:-1:flags=lanczos,split[a][b];[a]palettegen[p];[b][p]paletteuse" "${out}/nooko-${name}.gif"`);
}
p.kill();ws.close();process.exit(0);
