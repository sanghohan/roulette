/* 방문 수 — 페이지 맨 아래 <div id="visitCounter"> 에 표시. 하루 한 번만 센다 */
(function(){var b=(localStorage.getItem('pingpong_api')||'https://api.donjiral.net');
var today=new Date().toISOString().slice(0,10);var isNew=(localStorage.getItem('dj_visit_date')!==today);
if(isNew)localStorage.setItem('dj_visit_date',today);
fetch(b+'/api/visits',{method:isNew?'POST':'GET'}).then(function(r){return r.ok?r.json():null;}).then(function(d){
var el=document.getElementById('visitCounter');
if(el&&d&&typeof d.total==='number')el.textContent='👀 총 방문 '+d.total.toLocaleString()+' · 오늘 '+(Number(d.today)||0).toLocaleString();
}).catch(function(){});})();
