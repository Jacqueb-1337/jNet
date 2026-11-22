/* jNet Prism UI: live Android HostService state, not simulated network data. */
(() => {
'use strict';
const $ = id => document.getElementById(id);
const QA = typeof window.Native === 'undefined';
const native = window.Native || {};
const reduced = matchMedia('(prefers-reduced-motion: reduce)').matches;
let screen = 'dashboard', selectedMode = 'lan', pendingStart = false, sheetOpen = false, sheetExpanded = false;
let state = {active:false,status:'Idle',mode:'None',details:'',upstream:'',endpoint:'',portalUrl:'',sent:0,received:0,sentRate:0,receivedRate:0,connections:0,accepted:0,devices:0,log:'',lastUdp:0,startedAt:0};
let config = {mobileOnly:false,proxyPassword:'',wifiPassword:'',wifiBand:0,units:'MB/s',version:''};
const uploadSamples = Array(48).fill(0), downloadSamples = Array(48).fill(0);
let toastTimer, requestedTimer;
const navItems = [...document.querySelectorAll('[data-tab]')];
const navLens = $('dockLens'), panel = $('sheetPanel'), sheetLayer = $('sheetLayer'), sheetScroll = $('sheetScroll');
const safeNative = (fn,...args) => {
  if(typeof native[fn]==='function') {try {native[fn](...args)} catch(e) {toast('Could not complete action.')} }
  else toast('Preview only');
};
const icon = (id,name)=>$(id).setAttribute('href','#i-'+name);
const isRunning = () => !!state.active;
function toast(msg) {
  const el=$('toast');el.textContent=msg;el.classList.add('visible');
  clearTimeout(toastTimer);toastTimer=setTimeout(()=>el.classList.remove('visible'),2400);
}
function formatBytes(value) {
  const n=Math.max(0,Number(value)||0);
  if(n<1024) return Math.round(n)+' B';
  if(n<1048576) return (n/1024).toFixed(n>=102400?0:1)+' KB';
  if(n<1073741824) return (n/1048576).toFixed(1)+' MB';
  return (n/1073741824).toFixed(2)+' GB';
}
function formatSpeed(n) {
  const value=Math.max(0,Number(n)||0)*(config.units==='Mbps'?8:1)/1e6;
  return value>=100?Math.round(value).toString():value>=10?value.toFixed(1):value>=.01?value.toFixed(2):value>0?'<0.01':'0';
}
function curve(values,width,baseline,amplitude,max,below=false) {
  const unit=width/(values.length-1),factor=amplitude/Math.max(.01,max);
  return values.map((v,i)=>(i?'L':'M')+(i*unit).toFixed(1)+' '+(baseline+(below?1:-1)*v*factor).toFixed(1)).join(' ');
}
function setSpark(prefix,samples) {
  const max=Math.max(.1,...samples);
  const d=curve(samples,150,32,27,max);
  $(prefix+'Spark').setAttribute('d',d);
  $(prefix+'SparkFill').setAttribute('d',d+'L150 36L0 36Z');
}
function setChart() {
  const max=Math.max(.1,...uploadSamples,...downloadSamples);
  $('activityUp').setAttribute('d',curve(uploadSamples,332,92,76,max));
  $('activityDown').setAttribute('d',curve(downloadSamples,332,92,76,max,true));
}
function updateSpeeds() {
  $('uploadSpeed').textContent=formatSpeed(state.sentRate);
  $('downloadSpeed').textContent=formatSpeed(state.receivedRate);
  $('activityUpload').textContent=formatSpeed(state.sentRate);
  $('activityDownload').textContent=formatSpeed(state.receivedRate);
  for(const el of ['uploadUnit','downloadUnit','activityUploadUnit','activityDownloadUnit']) $(el).textContent=' '+config.units;
  $('toggleUnits').textContent=config.units;
}
function timeText() {
  if(!state.active) return 'Not running';
  const start=Number(state.startedAt);
  if(!start) return 'Starting…';
  const total=Math.max(0,Math.floor((Date.now()-start)/1000));
  const h=Math.floor(total/3600), m=Math.floor(total%3600/60), s=total%60;
  return h>0?h+':'+String(m).padStart(2,'0')+':'+String(s).padStart(2,'0'):String(m).padStart(2,'0')+':'+String(s).padStart(2,'0');
}
function renderState() {
  const connecting=isRunning() && state.status!=='Sharing';
  const running=isRunning() && state.status==='Sharing';
  $('statusBadge').className='pill'+(running?' green':connecting?' warn':'');
  $('statusBadgeText').textContent=running?'Connected':connecting?'Connecting':'Offline';
  $('sharingBadge').textContent=running?'Connected':connecting?'Connecting':'Offline';
  $('sharingBadge').className='pill'+(running?' green':connecting?' warn':'');
  if(isRunning()) selectedMode=state.mode==='Wi-Fi Direct'?'wifi':'lan';
  updateModeButtons();
  $('modeName').textContent=selectedMode==='lan'?'Current Wi-Fi':'Wi-Fi Direct';
  icon('modeIcon',selectedMode==='lan'?'wifi':'router');
  $('proxyLabel').textContent=state.endpoint?'SOCKS5 · '+state.endpoint.split(':').pop():'SOCKS5 · Port 1080';
  $('sessionTime').textContent=timeText();
  $('mainAction').setAttribute('aria-label',isRunning()?'Stop sharing':'Start sharing');
  icon('actionIcon',isRunning()?'stop':'play');
  $('mainAction').classList.toggle('pending',pendingStart);
  const message=state.active && state.status!=='Sharing'?state.status:'';
  $('connectionMessage').style.display=message?'block':'none';
  $('connectionMessage').textContent=message;
  $('endpointText').textContent=state.endpoint||'Start sharing to see an address';
  $('portalText').textContent=state.portalUrl||'Available when sharing starts';
  $('upstreamText').textContent=state.upstream||'Not connected';
  $('totalSent').textContent=formatBytes(state.sent);
  $('totalReceived').textContent=formatBytes(state.received);
  $('activeConnections').textContent=String(state.connections||0);
  $('acceptedConnections').textContent=String(state.accepted||0);
  $('deviceCount').textContent=(state.devices||0)+' client addresses';
  $('eventLog').textContent=state.log||'No activity yet.';
  $('mobileSummary').textContent=config.mobileOnly?'Current Wi-Fi: mobile data only':'Current Wi-Fi: use default internet';
  $('versionText').textContent='jNet'+(config.version?' v'+config.version:'');
  updateSpeeds();
}
function updateModeButtons() {
  for(const el of document.querySelectorAll('[data-mode]')) {
    const selected=el.dataset.mode===selectedMode;
    el.classList.toggle('selected',selected);el.setAttribute('aria-pressed',String(selected));
    el.disabled=isRunning();
  }
}
function switchTab(tab) {
  if(!['dashboard','sharing','activity','settings'].includes(tab) || (tab===screen && !sheetOpen)) return;
  if(sheetOpen) closeSheet();
  document.querySelector('#page-'+screen)?.classList.remove('active');
  screen=tab;
  const page=$('page-'+screen);page.classList.add('active');
  if(!reduced) {
    const transition=new Prism.SpringValue(0,{onUpdate:x=>{
      page.style.opacity=String(Math.max(0,Math.min(1,x)));
      page.style.transform='translate3d(0,'+((1-x)*13).toFixed(2)+'px,0)';
    }});
    transition.jump(0);transition.set(1,Prism.springs.dockSettle);
  } else {page.style.opacity='';page.style.transform=''}
  navItems.forEach(el=>{const active=el.dataset.tab===screen;el.classList.toggle('active',active);active?el.setAttribute('aria-current','page'):el.removeAttribute('aria-current')});
  $('headerTitle').textContent=screen==='dashboard'?'jNet':screen.charAt(0).toUpperCase()+screen.slice(1);
  $('pageScroller').scrollTop=0;
  setLens(navItems.findIndex(el=>el.dataset.tab===screen));
}
const lensPosition=new Prism.SpringValue(0,{onUpdate:value=>{
  navLens.style.transform='translate3d('+value.toFixed(2)+'px,0,0)';
}});
function setLens(i) {
  const w=navItems[0]?.getBoundingClientRect().width||80;
  if(reduced) lensPosition.jump(i*w);else lensPosition.set(i*w,Prism.springs.dockTravel);
}
window.addEventListener('resize',()=>{
  lensPosition.jump((navItems[0]?.getBoundingClientRect().width||80)*Math.max(0,navItems.findIndex(el=>el.dataset.tab===screen)));
});
navItems.forEach(el=>el.addEventListener('click',()=>switchTab(el.dataset.tab)));
function selectMode(mode) {
  if(isRunning()) {toast('Stop sharing to change modes.');return;}
  if(mode!=='wifi'&&mode!=='lan')return;
  selectedMode=mode;updateModeButtons();renderState();
}
document.querySelectorAll('[data-mode]').forEach(el=>el.addEventListener('click',()=>selectMode(el.dataset.mode)));
$('mainAction').addEventListener('click',()=>{
  if(pendingStart)return;
  if(isRunning()){safeNative('stop');return;}
  pendingStart=true;renderState();safeNative('start',selectedMode);
  clearTimeout(requestedTimer);requestedTimer=setTimeout(()=>{pendingStart=false;renderState()},2400);
});
function action(name) {
  switch(name) {
    case 'portalQR':if(!state.portalUrl)toast('Start sharing first.');else safeNative('qr','portal');break;
    case 'proxyQR':if(!state.endpoint)toast('Start sharing first.');else safeNative('qr','proxy');break;
    case 'copyEndpoint':if(!state.endpoint)toast('Start sharing first.');else safeNative('copy','endpoint');break;
    case 'openPortal':if(!state.portalUrl)toast('Start sharing first.');else safeNative('openPortal');break;
  }
}
document.querySelectorAll('[data-action]').forEach(el=>el.addEventListener('click',()=>action(el.dataset.action)));
$('shareDiagnostics').addEventListener('click',()=>safeNative('diagnostics'));
$('openAdvanced').addEventListener('click',()=>safeNative('advanced'));
$('toggleUnits').addEventListener('click',()=>{
  config.units=config.units==='MB/s'?'Mbps':'MB/s';
  updateSpeeds();safeNative('units',config.units);
});
const sheetMotion=new Prism.SpringValue(0,{onUpdate:x=>{
  const v=Math.max(0,Math.min(1,x));
  panel.style.transform='translate3d(0,'+((1-v)*105).toFixed(2)+'%,0)';
  $('sheetScrim').style.opacity=String(v);
}});
function openSheet() {
  if(sheetOpen)return;
  sheetOpen=true;sheetExpanded=false;panel.style.height='';panel.classList.remove('expanded');
  $('mobileOnly').checked=!!config.mobileOnly;syncSwitch();
  $('socksPassword').value=config.proxyPassword||'';
  $('wifiPassword').value=config.wifiPassword||'';
  $('wifiBand').value=String(config.wifiBand||0);
  $('settingsNotice').textContent=isRunning()?'Stop sharing to change settings.':'Changes apply when sharing starts.';
  $('sheetSave').disabled=isRunning();
  for(const id of ['mobileOnly','socksPassword','wifiPassword','wifiBand']) $(id).disabled=isRunning();
  sheetLayer.classList.add('visible');sheetLayer.setAttribute('aria-hidden','false');
  sheetScroll.scrollTop=0;sheetMotion.jump(0);
  requestAnimationFrame(()=>sheetMotion.set(1,Prism.springs.popup));
}
function closeSheet() {
  if(!sheetOpen)return;
  sheetOpen=false;
  if(reduced){sheetMotion.jump(0);finishClose()}
  else sheetMotion.set(0,Prism.springs.popup,finishClose);
}
function finishClose(){
  if(sheetOpen)return;
  sheetLayer.classList.remove('visible');sheetLayer.setAttribute('aria-hidden','true');
  panel.classList.remove('expanded');panel.style.height='';
}
function syncSwitch(){$('mobileSwitch').classList.toggle('checked',$('mobileOnly').checked)}
$('mobileOnly').addEventListener('change',syncSwitch);
$('editButton').addEventListener('click',openSheet);
$('openSettingsRow').addEventListener('click',openSheet);
$('sharingSettings').addEventListener('click',openSheet);
$('sheetClose').addEventListener('click',closeSheet);
$('sheetCancel').addEventListener('click',closeSheet);
$('sheetScrim').addEventListener('click',closeSheet);
for(const [button,input] of [['revealSocks','socksPassword'],['revealWifi','wifiPassword']])
  $(button).addEventListener('click',()=>{$(input).type=$(input).type==='password'?'text':'password'});
$('sheetSave').addEventListener('click',()=>{
  if(isRunning())return;
  const wifiPassword=$('wifiPassword').value, proxyPassword=$('socksPassword').value;
  if(!/^[\x20-\x7e]{8,63}$/.test(wifiPassword)) {toast('Network password must be 8 to 63 characters.');return;}
  if(new TextEncoder().encode(proxyPassword).length>255){toast('Proxy password is too long.');return;}
  safeNative('save',JSON.stringify({mobileOnly:$('mobileOnly').checked,proxyPassword,wifiPassword,wifiBand:Number($('wifiBand').value)}));
  if(QA){config={...config,mobileOnly:$('mobileOnly').checked,proxyPassword,wifiPassword,wifiBand:Number($('wifiBand').value)};closeSheet();renderState()}
});
/* The sheet follows a finger from the glass handle, expands before inner scrolling. */
const grip=$('sheetHandleWrap');let drag=null;
grip.addEventListener('pointerdown',e=>{
  if(e.button>0)return;
  drag={id:e.pointerId,at:e.clientY,started:panel.getBoundingClientRect().height,base:sheetExpanded};
  try{grip.setPointerCapture(e.pointerId)}catch(_){}
});
grip.addEventListener('pointermove',e=>{
  if(!drag||e.pointerId!==drag.id)return;
  const dy=e.clientY-drag.at;
  panel.style.height=Math.max(innerHeight*.42,Math.min(innerHeight-15,drag.started-dy))+'px';
  sheetMotion.jump(1);
});
function finishDrag(e){
  if(!drag||e.pointerId!==drag.id)return;
  const dy=e.clientY-drag.at,wasExpanded=drag.base;drag=null;panel.style.height='';
  if(dy>110&&!wasExpanded){closeSheet();return;}
  sheetExpanded=dy < -35?true:dy>50?false:wasExpanded;
  panel.classList.toggle('expanded',sheetExpanded);
}
grip.addEventListener('pointerup',finishDrag);grip.addEventListener('pointercancel',finishDrag);
sheetScroll.addEventListener('touchstart',e=>{
  if(!sheetExpanded&&sheetScroll.scrollTop===0)sheetScroll._startY=e.touches[0]?.clientY;
},{passive:true});
sheetScroll.addEventListener('touchmove',e=>{
  if(!sheetExpanded&&sheetScroll._startY!==undefined&&e.touches[0]?.clientY<sheetScroll._startY-14){
    sheetExpanded=true;panel.classList.add('expanded');
  }
},{passive:true});
function receive(data){
  if(!data||typeof data!=='object')return;
  state={...state,...data};
  if(pendingStart&&isRunning()){pendingStart=false;clearTimeout(requestedTimer)}
  uploadSamples.shift();downloadSamples.shift();
  uploadSamples.push(Math.max(0,Number(state.sentRate)||0));
  downloadSamples.push(Math.max(0,Number(state.receivedRate)||0));
  setSpark('upload',uploadSamples);setSpark('download',downloadSamples);setChart();renderState();
}
function configReceived(data) {
  if(!data||typeof data!=='object')return;
  config={...config,...data};renderState();
}
window.jNet={
  receive,config:configReceived,
  saved(){
    config={...config,mobileOnly:$('mobileOnly').checked,proxyPassword:$('socksPassword').value,wifiPassword:$('wifiPassword').value,wifiBand:Number($('wifiBand').value)};
    closeSheet();renderState();toast('Saved');
  },
  connectionRequested(){pendingStart=false;renderState()},
  handleBack(){if(sheetOpen){closeSheet();return true}if(screen!=='dashboard'){switchTab('dashboard');return true}return false},
  preview:QA
};
for(const el of document.querySelectorAll('.bouncy'))
  if(window.Prism&&Prism.elasticPress)Prism.elasticPress(el,{maxGrowth:7,pullPastEdges:true,hover:false});
setInterval(()=>{if(state.active)$('sessionTime').textContent=timeText()},1000);
requestAnimationFrame(()=>lensPosition.jump(0));
renderState();
})();

