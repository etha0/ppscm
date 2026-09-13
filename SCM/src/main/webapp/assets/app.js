'use strict';
const $=(selector,root=document)=>root.querySelector(selector);
const $$=(selector,root=document)=>[...root.querySelectorAll(selector)];
let toastTimer;
function toast(message){const node=$('#toast');if(!node)return;node.textContent=message;node.classList.add('visible');clearTimeout(toastTimer);toastTimer=setTimeout(()=>node.classList.remove('visible'),4500);}
$$('[data-back]').forEach(button=>button.addEventListener('click',()=>history.back()));
$$('[data-notice]').forEach(button=>button.addEventListener('click',()=>toast(button.dataset.notice)));
$('.password-toggle')?.addEventListener('click',event=>{const input=$('[name=password]');input.type=input.type==='password'?'text':'password';event.target.textContent=input.type==='password'?'보기':'숨김';event.target.setAttribute('aria-label','비밀번호 '+(input.type==='password'?'표시':'숨기기'));});
if($('[data-login]')){try{const username=localStorage.getItem('scm.username');if(username){$('[name=username]').value=username;$('#remember-username').checked=true;}}catch{}$('[data-login]').addEventListener('submit',()=>{try{if($('#remember-username').checked)localStorage.setItem('scm.username',$('[name=username]').value);else localStorage.removeItem('scm.username');}catch{}});}
function closeMenu(){document.body.classList.remove('menu-open');$('.menu-toggle')?.setAttribute('aria-expanded','false');}
$('.menu-toggle')?.addEventListener('click',()=>{const open=document.body.classList.toggle('menu-open');$('.menu-toggle').setAttribute('aria-expanded',String(open));});$('[data-close-menu]')?.addEventListener('click',closeMenu);document.addEventListener('keydown',event=>{if(event.key==='Escape')closeMenu();});
function filterTable(){const query=($('#table-search')?.value||'').trim().toLowerCase(),filter=$('#table-filter')?.value||'';let count=0;$$('#data-table tbody tr').forEach(row=>{row.hidden=!row.textContent.toLowerCase().includes(query)||!!filter&&($("#table-filter").dataset.filterKind==="category"?row.dataset.categoryId!==filter:!row.textContent.includes(filter));if(!row.hidden)count++;});$('#result-count').textContent=`검색 결과 ${count}건`;$('.empty-state').hidden=count>0;}
$('#table-search')?.addEventListener('input',filterTable);$('#table-filter')?.addEventListener('change',filterTable);$('#reset-filter')?.addEventListener('click',()=>{$('#table-search').value='';$('#table-filter').value='';filterTable();});
$$('[data-export]').forEach(button=>button.addEventListener('click',()=>{const table=$('table',button.closest('section'))||$('table');if(!table)return;const csv=$$('tr',table).filter(row=>!row.hidden).map(row=>$$('th,td',row).map(cell=>{let value=cell.textContent.trim().replace(/\s+/g,' ');if(/^[=+\-@]/.test(value))value="'"+value;return '"'+value.replace(/"/g,'""')+'"';}).join(',')).join('\r\n');const url=URL.createObjectURL(new Blob(['\uFEFF'+csv],{type:'text/csv;charset=utf-8'}));const link=document.createElement('a');link.href=url;link.download='부조뱅크_SCM.csv';link.click();setTimeout(()=>URL.revokeObjectURL(url),1000);}));
$('[data-close-dialog]')?.addEventListener('click',()=>$('#edit-dialog').close());
let previewUrls=[];
$('#product-images')?.addEventListener('change',event=>{const files=[...event.target.files];previewUrls.forEach(URL.revokeObjectURL);previewUrls=[];$('#image-preview').replaceChildren();if(files.length>6||files.some(file=>file.size>5*1024*1024||!['image/jpeg','image/png'].includes(file.type))){event.target.value='';toast('JPG, PNG 이미지를 각 5MB 이하, 최대 6장 선택하세요.');return;}files.forEach((file,index)=>{const img=document.createElement('img');img.src=URL.createObjectURL(file);previewUrls.push(img.src);img.alt=(index===0?'대표 이미지: ':'상품 이미지: ')+file.name;$('#image-preview').append(img);});});
let category='';
function filterCatalog(){const query=($('#catalog-search')?.value||'').trim().toLowerCase();let count=0;$$('.product-card').forEach(card=>{card.hidden=!!(category&&card.dataset.categoryId!==category)||!card.dataset.name.toLowerCase().includes(query);if(!card.hidden)count++;});$('#catalog-empty').hidden=count>0;}
$$('[data-category]').forEach(button=>button.addEventListener('click',()=>{category=button.dataset.category;$$('[data-category]').forEach(b=>b.classList.toggle('selected',b===button));filterCatalog();}));$('#catalog-search')?.addEventListener('input',filterCatalog);
const cart=new Map();
function cents(value){const parts=value.split('.');return BigInt(parts[0])*100n+BigInt(((parts[1]||'')+'00').slice(0,2));}
function money(value){const whole=value/100n,fraction=value%100n;return whole.toLocaleString('ko-KR')+(fraction?'.'+fraction.toString().padStart(2,'0'):'')+'원';}
function renderCart(){const root=$('#cart-items');root.replaceChildren();let total=0n;cart.forEach((item,id)=>{total+=item.price*BigInt(item.quantity);const row=document.createElement('div');row.className='cart-item';const header=document.createElement('header'),name=document.createElement('strong'),remove=document.createElement('button');name.textContent=item.name;remove.className='remove-item';remove.textContent='×';remove.setAttribute('aria-label',item.name+' 삭제');remove.onclick=()=>{cart.delete(id);renderCart();};header.append(name,remove);const footer=document.createElement('footer'),quantity=document.createElement('div');quantity.className='quantity';const minus=document.createElement('button'),input=document.createElement('input'),plus=document.createElement('button');minus.textContent='−';minus.setAttribute('aria-label',item.name+' 수량 줄이기');plus.textContent='+';plus.setAttribute('aria-label',item.name+' 수량 늘리기');input.type='number';input.min='1';input.max='100000';input.step='1';input.value=item.quantity;input.setAttribute('aria-label',item.name+' 발주 수량');minus.onclick=()=>{item.quantity=Math.max(1,item.quantity-1);renderCart();};plus.onclick=()=>{item.quantity=Math.min(100000,item.quantity+1);renderCart();};input.onchange=()=>{item.quantity=Math.max(1,Math.min(100000,Math.floor(Number(input.value)||1)));renderCart();};quantity.append(minus,input,plus);const amount=document.createElement('b');amount.textContent=money(item.price*BigInt(item.quantity));footer.append(quantity,amount);row.append(header,footer);root.append(row);});if(!cart.size){const empty=document.createElement('div');empty.className='cart-empty';empty.textContent='발주할 상품을 담아주세요.';root.append(empty);}$('#cart-count').textContent=cart.size;$('#cart-total').textContent=money(total);$('#checkout').disabled=!cart.size;}
$$('[data-add]').forEach(button=>button.addEventListener('click',()=>{if(button.disabled)return;const id=button.dataset.add;if(cart.has(id)){cart.get(id).quantity=Math.min(100000,cart.get(id).quantity+1);}else{if(cart.size>=100){toast('한 번에 최대 100개 상품을 발주할 수 있습니다.');return;}cart.set(id,{name:button.dataset.name,price:cents(button.dataset.price),quantity:1});}renderCart();toast(button.dataset.name+' 상품을 발주서에 담았습니다.');}));
$('#checkout')?.addEventListener('click',()=>{if(!cart.size)return;$('#dialog-title').textContent='발주 내용 확인';const fields=$('#dialog-fields'),summary=document.createElement('div');summary.className='dialog-summary';summary.textContent=$('#cart-store').textContent+' 발주서\n\n'+[...cart.values()].map(item=>`${item.name} × ${item.quantity} = ${money(item.price*BigInt(item.quantity))}`).join('\n')+'\n\n예상 합계 '+$('#cart-total').textContent+'\n요청사항: '+($('#order-note').value||'없음');fields.replaceChildren(summary);const hidden=(name,value)=>{const input=document.createElement('input');input.type='hidden';input.name=name;input.value=value;fields.append(input);};cart.forEach((item,id)=>{hidden('product_id',id);hidden('quantity',item.quantity);});hidden('note',$('#order-note').value);$('#edit-dialog').showModal();});
$$('form[data-confirm]').forEach(form=>form.addEventListener('submit',event=>{if(!confirm(form.dataset.confirm))event.preventDefault();}));
$$('form[method="post"]').forEach(form=>form.addEventListener('submit',event=>{if(event.defaultPrevented)return;$$('button:not([type="button"])',form).forEach(button=>{button.disabled=true;button.dataset.submitting='1';});}));
window.addEventListener('pageshow',()=>{$$('[data-submitting]').forEach(button=>{button.disabled=false;delete button.dataset.submitting;});});
const historyDialog=document.getElementById('inventory-history-dialog');
if(historyDialog){
  const content=document.getElementById('inventory-history-content');let pending;
  async function loadHistory(url){
    pending?.abort();const request=new AbortController();pending=request;
    content.textContent='재고 이력을 불러오는 중입니다.';content.setAttribute('aria-busy','true');
    if(!historyDialog.open)historyDialog.showModal();
    try{
      const response=await fetch(url,{signal:request.signal,credentials:'same-origin'});
      if(!response.ok||response.redirected)throw new Error('조회 실패');
      const html=await response.text();if(request.signal.aborted)return;
      const fragment=new DOMParser().parseFromString(html,'text/html').querySelector('[data-history-fragment]');
      if(!fragment)throw new Error('조회 실패');content.replaceChildren(document.importNode(fragment,true));historyDialog.scrollTop=0;
    }catch(error){if(error.name!=='AbortError'){
      const message=document.createElement('p');message.className='history-help';message.textContent='이력을 불러오지 못했습니다. 로그인 상태와 연결을 확인한 후 다시 시도하세요.';
      const retry=document.createElement('button');retry.type='button';retry.className='button';retry.textContent='다시 시도';retry.dataset.historyUrl=url;content.replaceChildren(message,retry);
    }}finally{if(pending===request)content.removeAttribute('aria-busy');}
  }
  document.addEventListener('click',event=>{const button=event.target.closest('[data-history-url]');if(button)loadHistory(button.dataset.historyUrl);});
  historyDialog.querySelector('[data-history-close]').addEventListener('click',()=>historyDialog.close());
  historyDialog.addEventListener('close',()=>pending?.abort());
  historyDialog.addEventListener('click',event=>{if(event.target!==historyDialog)return;const r=historyDialog.getBoundingClientRect();if(event.clientX<r.left||event.clientX>r.right||event.clientY<r.top||event.clientY>r.bottom)historyDialog.close();});
}
