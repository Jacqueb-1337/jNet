/* jNet Prism dock. Gesture path follows FlClash Prism v3 Dock:
 * elastic lift, velocity-projected fling, edge rubber band, jelly and glow.
 * Tap path preserves jNet's v0.1.32 dockTravel spring and resting geometry.
 * Original prototype modeled prism_ui/navigation_dock.dart, GPL-3.0-only. */
(function(global) {
  'use strict';
  const P = global.Prism;
  if (!P || !P.SpringValue) throw new Error('Prism motion engine is required before prism-dock.js');
  const clamp = P.clamp || ((x,a,b)=>Math.min(b,Math.max(a,x)));
  const rubberBand = P.rubberBand || ((v,l)=>Math.sign(v)*l*(1-1/(Math.abs(v)*.55/Math.max(l,.00001)+1)));
  const saturate = P.saturate || (v=>1-2/(Math.exp(2*v)+1));

  class PrismDockSelector {
    constructor(element, items, lens, onSelect, reduced = false) {
      this.el = element;
      this.items = items;
      this.lens = lens;
      this.onSelect = onSelect;
      this.reduced = reduced;
      this.index = Math.max(0, items.findIndex(i=>i.classList.contains('active')));
      this.pointer = null;
      this.pressed = null;
      this.dragging = false;
      this.releasing = false;
      this.lastX = 0;
      this.lastT = 0;
      this.velocityX = 0;
      this.sourceX = 0;
      this.glowAt = .5;
      const value = initial => new P.SpringValue(initial, {rest:.003,onUpdate:()=>this.paint()});
      this.pos=value(this.index);
      this.lift=value(0);
      this.jelly=value(0);
      this.swell=value(0);
      this.stretch=value(0);
      this.glow=value(0);
      this.spread=value(0);
      this.pos.onUpdate=()=>{this.wobble();this.paint();this.drop()};
      this.lift.onUpdate=()=>{this.paint();this.drop()};

      this.el.addEventListener('pointerdown', e=>this.down(e));
      this.el.addEventListener('pointermove', e=>this.move(e));
      this.el.addEventListener('pointerup', e=>this.end(e,true));
      this.el.addEventListener('pointercancel', e=>this.end(e,false));
      this.el.addEventListener('lostpointercapture', e=>this.end(e,false));
      this.el.addEventListener('contextmenu', e=>e.preventDefault());
      this.el.addEventListener('selectstart', e=>e.preventDefault());
      this.el.addEventListener('dragstart', e=>e.preventDefault());
      // Pointer taps have already committed on pointerup. Only synthesize
      // clicks for keyboard/accessibility, which report detail === 0.
      this.el.addEventListener('click', e=>{
        const item=e.target.closest('.dock-item');
        if (item && this.el.contains(item) && e.detail===0) this.select(this.items.indexOf(item));
      });
      this.el.addEventListener('keydown',e=>{
        if (e.key==='ArrowRight'||e.key==='ArrowLeft') {
          e.preventDefault();
          this.select(clamp(this.index+(e.key==='ArrowRight'?1:-1),0,this.items.length-1));
          this.items[this.index]?.focus();
        }
      });
      addEventListener('resize',()=>this.paint());
      this.paint();
    }
    layout() {
      const w = this.el.clientWidth;
      const count = this.items.length;
      const pitch = (w-12)/count; // jNet's original six-pixel dock inset
      const demoPitch = (w-8)/count;
      const demoLens = Math.max(84,demoPitch+8);
      return {
        w,pitch,tapWidth:pitch,tapHeight:this.el.clientHeight-12,
        demoPitch,demoLens,demoInset:(demoLens-demoPitch)/2+4,
        demoHeight:this.el.clientHeight-8
      };
    }
    locate(clientX) {
      const rect=this.el.getBoundingClientRect();
      const l=this.layout();
      const scale=rect.width/Math.max(1,l.w);
      const localX=(clientX-rect.left)/Math.max(.001,scale);
      let pos=(localX-4-l.demoInset)/l.demoPitch-.5;
      if (pos<0) return rubberBand(pos,.35);
      if (pos>this.items.length-1) return this.items.length-1+rubberBand(pos-this.items.length+1,.35);
      return pos;
    }
    indexAt(value) {return clamp(Math.round(value),0,this.items.length-1)}
    setSpring(node,target,description) {
      if (this.reduced) node.jump(target);
      else node.set(target,description);
    }
    clearSelection() {
      const selection=global.getSelection?.();
      if (selection && selection.rangeCount) selection.removeAllRanges();
    }
    down(e) {
      if (e.button!==0 || this.pointer!==null) return;
      // Stop native Android WebView text selection and touch callouts.
      e.preventDefault();
      this.clearSelection();
      this.pointer=e.pointerId;
      this.pressed=this.indexAt(this.locate(e.clientX));
      this.sourceX=e.clientX;
      this.lastX=e.clientX;
      this.lastT=performance.now();
      this.velocityX=0;
      this.dragging=false;
      this.releasing=false;
      this.glowAt=(e.clientX-this.el.getBoundingClientRect().left)/this.el.getBoundingClientRect().width;
      try{this.el.setPointerCapture(e.pointerId)}catch(_){}
    }
    move(e) {
      if(e.pointerId!==this.pointer) return;
      const now=performance.now(),dt=Math.max(1,now-this.lastT);
      this.velocityX=(e.clientX-this.lastX)*1000/dt;
      this.lastT=now;this.lastX=e.clientX;
      this.glowAt=(e.clientX-this.el.getBoundingClientRect().left)/Math.max(1,this.el.getBoundingClientRect().width);
      if(!this.dragging && Math.abs(e.clientX-this.sourceX)<9) return;
      if(!this.dragging) {
        this.dragging=true;
        this.el.classList.add('is-dragging');
        this.setSpring(this.pos,this.pressed,P.springs.dockTravel);
        this.setSpring(this.swell,1,P.springs.dockPress);
        this.setSpring(this.lift,1,P.springs.dockLift);
        this.setSpring(this.glow,1,P.springs.glowIn);
        this.spread.jump(0);
      }
      const position=this.locate(e.clientX),next=this.indexAt(position);
      if(next!==this.pressed){
        this.pressed=next;
        this.el.dispatchEvent(new CustomEvent('prism:selection-click',{detail:{index:next}}));
      }
      this.setSpring(this.pos,position,P.springs.dockFollow);
      const rect=this.el.getBoundingClientRect();
      const overshoot=e.clientX-clamp(e.clientX,rect.left,rect.right);
      this.setSpring(this.stretch,rubberBand(overshoot,Math.min(rect.width,rect.height)*7/32),P.springs.dockTrack);
      this.paint();
    }
    end(e,commit) {
      if(e.pointerId!==this.pointer) return;
      this.pointer=null;
      this.el.classList.remove('is-dragging');
      if(!this.dragging){
        if(commit) this.select(this.pressed);
        else this.moveTo(this.index);
        this.pressed=null;
        return;
      }
      let next=this.pressed;
      if(commit){
        const projected=this.pos.target+this.velocityX/this.layout().demoPitch*.1;
        next=clamp(Math.round(projected),this.pressed-1,this.pressed+1);
        next=clamp(next,0,this.items.length-1);
      }
      this.releasing=true;
      this.setSpring(this.pos,commit?next:this.index,P.springs.dockTravel);
      this.setSpring(this.stretch,0,P.springs.dockPress);
      this.setSpring(this.swell,0,P.springs.dockPress);
      this.setSpring(this.glow,0,P.springs.glowOut);
      this.setSpring(this.spread,1,P.springs.glowOut);
      if(commit && next!==this.index) this.select(next,true);
      if(this.reduced) this.lift.jump(0);
      this.drop();
      this.dragging=false;
      this.pressed=null;
    }
    drop() {
      if(this.releasing && this.pointer===null && this.lift.x>=.975 &&
          Math.abs(this.pos.target-this.pos.x)<.04){
        this.releasing=false;
        this.setSpring(this.lift,0,P.springs.dockLift);
      }
    }
    wobble() {
      if(!this.dragging && !this.releasing)return;
      const velocity=this.pos.v,acceleration=this.pos.acceleration;
      const brake=Math.max(0,-acceleration*Math.sign(velocity));
      const target=.2*saturate(Math.abs(velocity)/3)-.15*saturate(brake/25);
      if(Math.abs(this.jelly.target-target)>.002) this.setSpring(this.jelly,target,P.springs.dockJelly);
    }
    moveTo(index) {
      if (index<0 || index>=this.items.length)return;
      if(this.pointer!==null)return;
      this.index=index;
      this.setSpring(this.pos,index,P.springs.dockTravel);
      this.paint();
    }
    select(index,fromPointer=false) {
      if(index<0 || index>=this.items.length)return;
      if(index===this.index){
        if(!fromPointer) this.moveTo(index);
        return;
      }
      this.index=index;
      if(!fromPointer) this.setSpring(this.pos,index,P.springs.dockTravel);
      this.onSelect(index,fromPointer);
    }
    paint() {
      const l=this.layout();
      if(!l.w)return;
      const lift=clamp(this.lift.x,0,1),stretch=this.jelly.x;
      // At rest these are EXACTLY jNet's old six-pixel lens geometry.
      // At full drag lift, geometry is the demo's _LensRect.
      const tapCenter=6+(this.pos.x+.5)*l.pitch;
      const demoCenter=l.demoInset+(this.pos.x+.5)*l.demoPitch;
      const center=tapCenter+(demoCenter-tapCenter)*lift;
      const dragWidth=(l.demoLens+16*lift)*(1+stretch);
      const dragHeight=(l.demoHeight+16*lift)*(1-stretch*1.4);
      const width=l.tapWidth+(dragWidth-l.tapWidth)*lift;
      const height=l.tapHeight+(dragHeight-l.tapHeight)*lift;
      const demoTop=(l.demoHeight-dragHeight)/2+4;
      const top=6+(demoTop-6)*lift;
      const x=center-width/2;
      Object.assign(this.lens.style,{
        width:width+'px',height:height+'px',left:x+'px',top:top+'px',
        transform:'none',opacity:'1'
      });
      this.lens.style.setProperty('--lens-lift',String(lift));
      this.lens.style.setProperty('--lens-glow',String(this.glow.x));
      this.el.style.setProperty('--dock-press',String(this.swell.x));
      this.el.style.setProperty('--dock-scale',String(1+Math.min(.25,16/Math.max(l.w,l.demoHeight))*this.swell.x));
      this.el.style.setProperty('--glow-x',(this.glowAt*100)+'%');
      this.el.style.setProperty('--glow-alpha',String(.06*this.glow.x*this.glow.x));
      this.el.style.setProperty('--glow-radius',(28*(1+3*this.spread.x))+'px');
      this.items.forEach((item,index)=>{
        const proximity=Math.max(0,1-Math.abs(this.pos.x-index)*l.demoPitch/(l.demoLens/2+16*lift));
        item.style.setProperty('--selected-proximity',String(proximity));
        item.style.setProperty('--selected-proximity-pct',(proximity*100)+'%');
        item.style.setProperty('--lift-proximity',String(lift*proximity));
      });
    }
  }
  global.PrismDockSelector=PrismDockSelector;
})(window);
