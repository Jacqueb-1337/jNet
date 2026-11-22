
/* Prism JS refractive surfaces. Source: prism_ui liquid_glass.dart, liquid_glass.frag
 * This path renders a real, live, browser-composited BACKDROP using SVG feDisplacementMap.
 * No DOM screenshots or stale textures. Chromium implementation; other engines use native
 * frost-only backdrop-filter until they implement SVG backdrop displacement.
 * The optical map follows the source's rounded-box signed distance, circular bevel,
 * negative lens refraction, saturation, translucency and optional RGB splitting.
 * Chrome's SVG backdrop sampling is not equivalent to Impeller's LiquidGlassHost atlases.
 * Dock layers are sibling surfaces so backdrop displacement sees original
 * page content without a nested filter root shifting the sampled region.
 */
(function(){
'use strict';
const NS='http://www.w3.org/2000/svg';
const create=(tag,attrs={})=>{const el=document.createElementNS(NS,tag);Object.entries(attrs).forEach(([k,v])=>el.setAttribute(k,String(v)));return el};
const styles={
  pill:{refraction:60,bevel:20,blur:0,haze:.72,hazeBlur:15.6,saturation:1.925,brightness:1,tint:[74,75,81,.48]},
  regular:{refraction:60,bevel:20,blur:2.5,haze:0,hazeBlur:0,saturation:2.84,brightness:1,tint:[75,76,83,.62]},
  menu:{refraction:60,bevel:20,blur:12,haze:0,hazeBlur:0,saturation:1.75,brightness:1.8,tint:[70,70,75,.63]},
  lens:{refraction:-4.3,bevel:5.5,blur:0,haze:0,hazeBlur:0,saturation:1,brightness:1.33,tint:[0,0,0,0],dispersion:6.5},
  tinted:{refraction:60,bevel:20,blur:2.5,haze:0,hazeBlur:0,saturation:1.2,brightness:1,tint:[50,136,247,.82]},
  header:{refraction:19,bevel:30,blur:0,haze:0,hazeBlur:0,saturation:1.18,brightness:1,tint:[18,19,24,.45]}
};
const mix=(a,b,t)=>a+(b-a)*t;
const smooth=(a,b,x)=>{let t=Math.max(0,Math.min(1,(x-a)/(b-a||1)));return t*t*(3-2*t)};
// Chromium's feDisplacementMap reads feImage's decoded LINEAR channel values,
// not the 0..255 sRGB byte value. A neutral displacement therefore requires
// sRGB(0.5) ~= 188, not 128. Using 128 caused a ~42px false displacement.
// Generate the transfer function once. Avoid hundreds of thousands of
// Math.pow() calls when rebuilding optical maps on a phone.
const displacementLut=new Uint8Array(4097);
for(let i=0;i<=4096;i++){
  const linear=i/4096;
  const encoded=linear<=0.0031308?12.92*linear:1.055*Math.pow(linear,1/2.4)-0.055;
  displacementLut[i]=Math.round(encoded*255);
}
function displacementByte(delta,scale){
  const linear=Math.max(0,Math.min(1,0.5+delta/scale));
  return displacementLut[Math.round(linear*4096)];
}
function shape(x,y,w,h,r){
  const hx=w/2,hy=h/2,qx=Math.abs(x)-hx+r,qy=Math.abs(y)-hy+r;
  const d=Math.hypot(Math.max(0,qx),Math.max(0,qy))+Math.min(Math.max(qx,qy),0)-r;
  let nx,ny;
  if(qx>0&&qy>0){let len=Math.hypot(qx,qy);nx=qx/len;ny=qy/len}else if(qx>qy){nx=1;ny=0}else{nx=0;ny=1}
  return {inset:Math.max(-d,0),nx:nx*(x<0?-1:1),ny:ny*(y<0?-1:1)};
}
function mapImage(w,h,material,channel){
  const m=styles[material],pw=Math.min(550,Math.max(96,Math.round(w*1.3))),ph=Math.min(550,Math.max(64,Math.round(h*1.3)));
  const c=document.createElement('canvas');c.width=pw;c.height=ph;
  const cx=c.getContext('2d',{willReadFrequently:false}),img=cx.createImageData(pw,ph),data=img.data;
  const radius=Math.min(w,h)*.5, minor=Math.min(w,h)*.5,bevel=Math.max(.001,Math.min(m.bevel,minor*.5));
  const scale=material==='lens'?18:material==='header'?56:150;
  for(let iy=0;iy<ph;iy++)for(let ix=0;ix<pw;ix++){
    const x=(ix+.5)/pw*w-w*.5,y=(iy+.5)/ph*h-h*.5;
    let dx,dy,inset=0,nx=0,ny=0;
    if(material==='header'){
      // Vertical optical meniscus: displace content entering the header,
      // then return to neutral before the feathered lower edge.
      const py=(iy+.5)/ph*h,px=(ix+.5)/pw*w;
      const band=smooth(22,79,py)*(1-smooth(h-40,h-4,py));
      dx=2.4*Math.sin(px/w*Math.PI*2)*band;
      dy=-16*band;
    }else{
      ({inset,nx,ny}=shape(x,y,w,h,radius));
      const rise=1-Math.min(inset/bevel,1);
      if(m.refraction<0){const amount=-m.refraction*smooth(0,bevel,inset)*(1-smooth(bevel,minor,inset));dx=nx*amount;dy=ny*amount}
      else{const amount=Math.min(m.refraction,minor)*(1-Math.sqrt(Math.max(0,1-rise*rise)));dx=-nx*amount;dy=-ny*amount}
    }
    if(channel&&inset<10){const split=(m.dispersion||0)*.5*(.6+.4*Math.abs(ny)*Math.max(1-inset/7,0))*(1-smooth(7,10,inset));dx+=(channel==='red'?1:-1)*ny*split;dy+=(channel==='red'?1:-1)*nx*split;}
    const k=(iy*pw+ix)*4;
    data[k]=displacementByte(dx,scale);
    data[k+1]=displacementByte(dy,scale);
    data[k+2]=127;data[k+3]=255;
  }
  cx.putImageData(img,0,0);return {url:c.toDataURL('image/png'),scale};
}
function matrix(kind){
  const r=kind==='red',g=kind==='green',b=kind==='blue';
  return [(r?'1 0 0 0 0':'0 0 0 0 0'),(g?'0 1 0 0 0':'0 0 0 0 0'),(b?'0 0 1 0 0':'0 0 0 0 0'),'0 0 0 1 0'].join(' ');
}
class GlassHost{
  constructor(){this.enabled=true;this.nodes=new Map();this.seq=0;this.root=create('svg',{width:0,height:0,'aria-hidden':'true',style:'position:absolute;width:0;height:0;overflow:hidden;pointer-events:none;z-index:-1'});this.defs=create('defs');this.root.append(this.defs);document.body.append(this.root);
    this.observer=new ResizeObserver(entries=>{for(const entry of entries){const n=this.nodes.get(entry.target);if(n)this.schedule(n)}});
    addEventListener('resize',()=>{this.nodes.forEach(n=>this.schedule(n))});
  }
  bind(element,type){if(!element||this.nodes.has(element))return;const id='prism-glass-'+(++this.seq),filter=create('filter',{id,x:0,y:0,width:1,height:1,filterUnits:'userSpaceOnUse',primitiveUnits:'userSpaceOnUse',colorInterpolationFilters:'sRGB'});this.defs.append(filter);
    const node={element,type,id,filter,queued:0,lastW:0,lastH:0};this.nodes.set(element,node);element.classList.add('prism-optical');element.dataset.opticalType=type;this.observer.observe(element);this.schedule(node);return node;
  }
  schedule(n){
    if(n.queued)return;
    n.queued=requestAnimationFrame(()=>{
      n.queued=0;
      this.update(n);
    });
  }
  update(n){if(!n.element.isConnected)return;const r=n.element.getBoundingClientRect(),w=Math.max(1,r.width),h=Math.max(1,r.height);if(w<3||h<3)return;
    if(Math.abs(w-n.lastW)<1&&Math.abs(h-n.lastH)<1)return;
    n.lastW=w;n.lastH=h;
    const m=styles[n.type];if(!m)return;
    // Displacement can reach outside the element near rounded edges. Expand the
    // filter capture region in the SAME local coordinate space as feImage.
    const gutter=Math.ceil(Math.max(35,Math.min(100,Math.abs(m.refraction)+m.blur*3)));
    n.filter.setAttribute('x',String(-gutter));
    n.filter.setAttribute('y',String(-gutter));
    n.filter.setAttribute('width',String(w+2*gutter));
    n.filter.setAttribute('height',String(h+2*gutter));
    n.element.dataset.glassMode='refractive';
    const image=mapImage(w,h,n.type),f=n.filter;f.replaceChildren();
    let source='SourceGraphic';
    if(m.blur>0){f.append(create('feGaussianBlur',{in:source,stdDeviation:m.blur,result:'frost'}));source='frost'}
    if(m.haze>0){f.append(create('feGaussianBlur',{in:'SourceGraphic',stdDeviation:m.hazeBlur,result:'haze'}));f.append(create('feComposite',{in:source,in2:'haze',operator:'arithmetic',k1:0,k2:1-m.haze,k3:m.haze,k4:0,result:'mixFrost'}));source='mixFrost'}
    const addDrop=(name,img)=>{
      // An feImage only covers the element rect; outside it Chromium reads 0,
      // which turns into an enormous false displacement at rounded edges.
      // Extend with a true linear-neutral map before compositing the texture.
      f.append(create('feFlood',{'flood-color':'#bcbcbc',result:name+'Neutral'}));
      f.append(create('feImage',{href:img.url,x:0,y:0,width:w,height:h,preserveAspectRatio:'none',result:name+'Map'}));
      f.append(create('feComposite',{in:name+'Map',in2:name+'Neutral',operator:'over',result:name+'SafeMap'}));
      f.append(create('feDisplacementMap',{in:source,in2:name+'SafeMap',scale:img.scale,xChannelSelector:'R',yChannelSelector:'G',result:name+'Drop'}));
    };
    addDrop('base',image);
    if(m.dispersion&&document.body.dataset.glass==='ultra'){
      addDrop('red',mapImage(w,h,n.type,'red'));addDrop('blue',mapImage(w,h,n.type,'blue'));
      f.append(create('feColorMatrix',{in:'redDrop',type:'matrix',values:matrix('red'),result:'onlyRed'}));
      f.append(create('feColorMatrix',{in:'baseDrop',type:'matrix',values:matrix('green'),result:'onlyGreen'}));
      f.append(create('feColorMatrix',{in:'blueDrop',type:'matrix',values:matrix('blue'),result:'onlyBlue'}));
      f.append(create('feComposite',{in:'onlyRed',in2:'onlyGreen',operator:'arithmetic',k1:0,k2:1,k3:1,k4:0,result:'redGreen'}));
      f.append(create('feComposite',{in:'redGreen',in2:'onlyBlue',operator:'arithmetic',k1:0,k2:1,k3:1,k4:0,result:'refracted'}));
    }
    if(m.saturation!==1)f.append(create('feColorMatrix',{in:m.dispersion&&document.body.dataset.glass==='ultra'?'refracted':'baseDrop',type:'saturate',values:m.saturation,result:'colored'}));
    const filterURL=`url("#${n.id}")`;
    n.element.style.backdropFilter=filterURL;
    n.element.style.webkitBackdropFilter=filterURL;
    // The native SVG filter operates on the live compositor backdrop.
    n.element.style.setProperty('--optical-tint',`rgba(${m.tint[0]},${m.tint[1]},${m.tint[2]},${m.tint[3]})`);
  }
  quality(mode){this.enabled=mode!=='none';if(!this.enabled){this.nodes.forEach(n=>{n.element.style.removeProperty('backdrop-filter');n.element.style.removeProperty('-webkit-backdrop-filter')});return}for(const n of this.nodes.values()){n.lastW=0;this.update(n)}}
  destroy(){this.observer.disconnect();this.nodes.forEach(n=>{cancelAnimationFrame(n.queued);n.filter.remove()});this.nodes.clear();this.root.remove()}
}
let glassHost;
function enable(){if(glassHost)return glassHost;glassHost=new GlassHost();const reg=(sel,type)=>document.querySelectorAll(sel).forEach(el=>glassHost.bind(el,type));reg('#dockGlassBase','pill');reg('#dockLens','lens');reg('#headerGlass','header');reg('.circle-action','regular');reg('.prism-popup-platter','menu');reg('#popupMenu','menu');reg('#sheetPanel','menu');reg('.status-deck .deck-card','menu');const n=document.querySelector('.status-deck');if(!n){new MutationObserver(()=>{reg('.status-deck .deck-card','menu')}).observe(document.body,{childList:true,subtree:false})}
 return glassHost;}
window.PrismGlass={init:enable,get host(){return glassHost},styles,shape,mapImage};
// Wait until DOM and the app are initialized, so source elements already exist.
if(document.readyState==='loading')addEventListener('DOMContentLoaded',()=>requestAnimationFrame(enable));else requestAnimationFrame(enable);
})();

