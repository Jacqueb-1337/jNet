
/* Prism JS: independent browser implementation of the motion primitives used by
   chen08209/prism_ui, pinned by FlClash main at 6c47901cc80b5325bdb9650579ca2e000a60b39e.
   This is a behavioral port, not a copy of Dart or an embedded Flutter runtime.
   Source-derived behavior and implementation distributed under GPL-3.0-only.
   Original FlClash/prism_ui authors retain their copyright. */
(function(global){'use strict';
 const TAU=2*Math.PI, EPS=1e-8;
 const clamp=(v,min,max)=>Math.min(max,Math.max(min,v));
 const lerp=(a,b,t)=>a+(b-a)*t;
 const easeOutCubic=t=>1-Math.pow(1-clamp(t,0,1),3);
 const easeInOut=t=>{t=clamp(t,0,1);return t*t*(3-2*t)};
 const cubicBezier=(x1,y1,x2,y2)=>{
   // Inverse x(t) by Newton iterations, with bisection for nearly vertical curves.
   const xyz=(a,b,t)=>3*a*(1-t)*(1-t)*t+3*b*(1-t)*t*t+t*t*t;
   return x=>{let t=clamp(x,0,1);for(let i=0;i<7;i++){let dx=(xyz(x1,x2,t+0.0001)-xyz(x1,x2,t-0.0001))/0.0002;if(Math.abs(dx)<1e-5)break;let next=t-(xyz(x1,x2,t)-x)/dx;if(next<0||next>1)break;t=next}return xyz(y1,y2,t)};
 };
 const appleEase=cubicBezier(.25,.1,.25,1);
 // Same parameter conversions as Flutter 3.47's SpringDescription.
 const description=(mass,stiffness,ratio)=>({mass,stiffness,ratio});
 const withDurationAndBounce=(seconds,bounce=0)=>description(1,4*Math.PI*Math.PI/(seconds*seconds),bounce>0?1-bounce:1/(bounce+1));
 const springs=Object.freeze({
   popup:description(1,400,.754), corners:description(1,324,.75), droplet:description(1,165,.8),
   dockPress:description(1,311,.593), dockFollow:description(1,1024,.9), dockJelly:description(1,100,.4),
   dockTrack:withDurationAndBounce(.120),dockLift:withDurationAndBounce(.250),dockTravel:withDurationAndBounce(.400,.15),
   dockSettle:withDurationAndBounce(.500,.32),dockHover:withDurationAndBounce(.260,.18),
   fade:withDurationAndBounce(.200),glowIn:withDurationAndBounce(.100),glowOut:withDurationAndBounce(.500),
   sheet:description(3,1000,1),sheetThrown:description(3,1000,.8)
 });
 // Analytic solution for under-, critically, and over-damped oscillators.
 // This preserves the velocity and phase exactly when a pointer retargets a spring.
 function sampleSpring(desc,x0,x1,v0,t){
   if(t<=0)return {x:x0,v:v0};
   const w=Math.sqrt(desc.stiffness/desc.mass),z=desc.ratio,d=x0-x1;
   if(z<1-1e-6){const a=z*w,wd=w*Math.sqrt(1-z*z),c=(v0+a*d)/wd,E=Math.exp(-a*t),co=Math.cos(wd*t),si=Math.sin(wd*t);
     return {x:x1+E*(d*co+c*si),v:E*((-a*d+c*wd)*co+(-a*c-d*wd)*si)};}
   if(z>1+1e-6){const s=Math.sqrt(z*z-1),r1=-w*(z-s),r2=-w*(z+s),c1=(v0-r2*d)/(r1-r2),c2=d-c1,e1=Math.exp(r1*t),e2=Math.exp(r2*t);return {x:x1+c1*e1+c2*e2,v:r1*c1*e1+r2*c2*e2};}
   const e=Math.exp(-w*t),b=v0+w*d;return {x:x1+(d+b*t)*e,v:(b-w*(d+b*t))*e};
 }
 const running=new Set();let raf=0;
 const ticker=ms=>{raf=0;for(const s of [...running])s._step(ms);if(running.size)raf=requestAnimationFrame(ticker)};
 class SpringValue {
  constructor(initial=0,{rest=.001,onUpdate=null}={}){this.x=initial;this.v=0;this.target=initial;this.desc=springs.dockTravel;this.start=0;this.x0=initial;this.v0=0;this.rest=rest;this.onUpdate=onUpdate;this.onRest=null;this.active=false}
  set(target,desc=springs.dockTravel,onRest=null){const now=performance.now();if(this.active)this._sample(now);this.x0=this.x;this.v0=this.v;this.start=now;this.target=target;this.desc=desc;this.onRest=onRest;this.active=true;running.add(this);if(!raf)raf=requestAnimationFrame(ticker);return this}
  jump(x){this.x=this.x0=this.target=x;this.v=this.v0=0;this.active=false;running.delete(this);this.onUpdate?.(x,this);return this}
  _sample(ms){const q=sampleSpring(this.desc,this.x0,this.target,this.v0,Math.max(0,(ms-this.start)/1000));this.x=q.x;this.v=q.v;return q}
  _step(ms){this._sample(ms);const tolerance=this.rest*Math.sqrt(this.desc.stiffness/this.desc.mass);if(Math.abs(this.x-this.target)<=this.rest&&Math.abs(this.v)<=tolerance){this.x=this.target;this.v=0;this.active=false;running.delete(this);this.onUpdate?.(this.x,this);const f=this.onRest;this.onRest=null;f?.()}else this.onUpdate?.(this.x,this)}
  get acceleration(){return(this.desc.stiffness*(this.target-this.x)-2*this.desc.ratio*Math.sqrt(this.desc.mass*this.desc.stiffness)*this.v)/this.desc.mass}
 }
 const timed=(duration,update,complete)=>{
   const start=performance.now(),reduced=matchMedia('(prefers-reduced-motion: reduce)').matches;
   let stopped=false;
   const go=now=>{if(stopped)return;let t=reduced?1:clamp((now-start)/(duration*1000),0,1);update(t,now);if(t<1)requestAnimationFrame(go);else complete?.()};
   requestAnimationFrame(go);return()=>{stopped=true};
 };
 const riseAndFall=(t,peak,sharpness)=>t<=0?0:Math.pow(t/peak,sharpness)*Math.exp(sharpness*(1-t/peak));
 const rubberBand=(overshoot,limit)=>Math.sign(overshoot)*limit*(1-1/(Math.abs(overshoot)*.55/Math.max(limit,EPS)+1));
 const saturate=v=>1-2/(Math.exp(2*v)+1);
 // Apple-style elastic press. A quick tap completes the entire swell before release.
 function elasticPress(el,{maxGrowth=16,pullPastEdges=false,hover=true}={}){
  let pointer=null,origin=null,releasing=false,hovers=0;
  const lift=new SpringValue(0),hov=new SpringValue(0),x=new SpringValue(0),y=new SpringValue(0);
  const paint=()=>{let r=el.getBoundingClientRect(),side=Math.max(r.width,r.height,1),amount=lift.x+(1-lift.x)*hov.x;let grow=Math.min(.25,maxGrowth/side)*amount;
    el.style.setProperty('--press-scale',`${1+grow}`);el.style.setProperty('--press-x',`${x.x}px`);el.style.setProperty('--press-y',`${y.x}px`);
    el.style.setProperty('--press-level',`${lift.x}`);
    if(releasing&&lift.x>=.975){releasing=false;lift.set(0,springs.dockPress)}
  };
  for(const v of [lift,hov,x,y])v.onUpdate=paint;
  const start=e=>{if(pointer!==null||e.button>0)return;pointer=e.pointerId;origin={x:e.clientX,y:e.clientY};releasing=false;lift.set(1,springs.dockPress);el.classList.add('is-pressing')};
  const move=e=>{if(pointer!==e.pointerId)return;const r=el.getBoundingClientRect();let dx=e.clientX-origin.x,dy=e.clientY-origin.y;
   if(pullPastEdges){dx=e.clientX-clamp(e.clientX,r.left,r.right);dy=e.clientY-clamp(e.clientY,r.top,r.bottom)}
   let lim=Math.min(r.width,r.height)*7/32;x.set(rubberBand(dx,lim),springs.dockTrack);y.set(rubberBand(dy,lim),springs.dockTrack)};
  const end=e=>{if(pointer!==e.pointerId)return;pointer=null;el.classList.remove('is-pressing');x.set(0,springs.dockPress);y.set(0,springs.dockPress);releasing=true;if(lift.x>=.975){releasing=false;lift.set(0,springs.dockPress)}};
  el.addEventListener('pointerdown',start);el.addEventListener('pointermove',move);el.addEventListener('pointerup',end);el.addEventListener('pointercancel',end);
  if(hover){el.addEventListener('pointerenter',e=>{if(e.pointerType==='touch')return;hovers++;hov.set(.4,springs.dockSettle)});el.addEventListener('pointerleave',e=>{if(e.pointerType==='touch')return;hovers=Math.max(0,hovers-1);hov.set(hovers?.4:0,springs.dockSettle)})}
  return {lift,hov,x,y};
 }
 function rectFromCenter(x,y,w,h){return {x:x-w/2,y:y-h/2,w,h,cx:x,cy:y}}
 function centerOf(r){return {x:r.x+r.w/2,y:r.y+r.h/2}}
 function interpolateRect(a,b,t){return {x:lerp(a.x,b.x,t),y:lerp(a.y,b.y,t),w:lerp(a.w,b.w,t),h:lerp(a.h,b.h,t)}}
 function popupOpening(t,anchor,card){
   const s=sampleSpring(springs.popup,0,1,0,t).x;
   const corner=sampleSpring(springs.corners,1,0,0,Math.max(0,t-.07)).x;
   const sc=lerp(.41*Math.min(anchor.w,anchor.h)/card.w,1,s);
   const height=lerp(card.w,card.h,s),from=centerOf(anchor),to=centerOf(card),delta=to.y-from.y;
   const dip=Math.sign(delta)*(Math.min(1,Math.abs(delta)/30)*23+.124*Math.abs(delta))*riseAndFall(t,.098,3.3);
   const beyondW=Math.max(0,anchor.w-anchor.h)*(1-s),beyondH=Math.max(0,anchor.h-anchor.w)*(1-s);
   const rect=rectFromCenter(lerp(from.x,to.x,s),lerp(from.y,to.y,s)+dip,Math.max(0,card.w*sc+beyondW),Math.max(0,height*sc+beyondH));
   const corners=(24+card.w*.48*corner);const radius=clamp(Math.min(height/2,corners)*sc,0,1000);
   return {rect,radius,progress:s,content:clamp(s,0,1),blur:Math.hypot(4*(1-clamp(s,0,1)),1.9*riseAndFall(t,.069,2.1)),dropletProgress:s};
 }
 function popupClosing(t,anchor,from){
   const drop=sampleSpring(springs.droplet,0,1,0,t).x;
   const ringing=1-easeInOut(clamp((t-.4)/.1,0,1)),p=1+(drop-1)*ringing;
   const a=centerOf(anchor),c=centerOf(from),d=c.y-a.y;
   const dip=Math.min(.21*Math.abs(d),22)*riseAndFall(t,.072,2.4);
   const lift=Math.min(.25*Math.abs(d),19)*riseAndFall(t,.176,4.9);
   const scale=lerp(1,.41*Math.min(anchor.w,anchor.h)/Math.max(from.w,from.h),p);
   const beyondW=Math.max(0,anchor.w-anchor.h)*p,beyondH=Math.max(0,anchor.h-anchor.w)*p;
   const rect=rectFromCenter(lerp(c.x,a.x,p),lerp(c.y,a.y,p)+Math.sign(d)*dip*ringing,Math.max(0,from.w*scale+beyondW),Math.max(0,lerp(from.h,from.w,p)*scale+beyondH));
   const radius=lerp(24,from.w/2,p)*scale;
   const absorbed=easeInOut(clamp((t/.5-.6)/.3,0,1));
   return {rect,radius,progress:p,content:1-easeOutCubic(clamp(t/.1,0,1)),blur:8*easeOutCubic(clamp(t/.1,0,1)),absorbed,droplet:{x:lerp(c.x,a.x,p),y:lerp(c.y,a.y,p)-Math.sign(d)*lift*ringing,w:lerp(anchor.w*.25,anchor.w,p),h:lerp(anchor.h*.25,anchor.h,p)}};
 }
 const styles=Object.freeze({
  regularDark:{blur:2.5,saturation:2.84,tint:'.62',refraction:60,bevel:20,rim:.28},
  menuDark:{blur:12,saturation:1.75,brightness:1.8,tint:'.63',refraction:60,bevel:20,rim:0},
  pillDark:{blur:0,haze:.72,hazeBlur:15.6,saturation:1.925,tint:'.48',refraction:60},
  lensDark:{blur:0,refraction:-4.3,bevel:5.5,dispersion:6.5,brightness:1.33,rim:.3},
  alertDark:{blur:16,saturation:1.75,brightness:1.8,tint:'.63',refraction:60}
 });
 global.Prism={clamp,lerp,easeOutCubic,easeInOut,cubicBezier,appleEase,description,withDurationAndBounce,
    springs,sampleSpring,SpringValue,timed,riseAndFall,rubberBand,saturate,
    elasticPress,popupOpening,popupClosing,rectFromCenter,centerOf,interpolateRect,styles};
})(window);

