/*****YTPRO*******
Author: Syed Haseeb (Decoderium Technologies)
Version: 3.9.2
URI: https://github.com/SyedHaseeb1/yt-pro-decoderium
*/

if (typeof MediaMetadata === 'undefined') {
window.MediaMetadata = class {
constructor(data = {}) {
this.title = data.title || '';
this.artist = data.artist || '';
this.album = data.album || '';
this.artwork = data.artwork || [];
}
};

}



if (!('mediaSession' in navigator) || true) { // Always override to ensure integration with Android bridge

window.handlers = window.handlers || {};
window.serviceRunning = window.serviceRunning || false;

let _state = (navigator.mediaSession && navigator.mediaSession.playbackState) || 'none';
let _metadata = (navigator.mediaSession && navigator.mediaSession.metadata) || null;

if (!navigator.mediaSession) {
    Object.defineProperty(navigator, 'mediaSession', {
        value: {},
        configurable: true
    });
}

// Store original setActionHandler if it exists
const originalSetActionHandler = navigator.mediaSession.setActionHandler;

navigator.mediaSession.setActionHandler = (action, handler) => {
    if (typeof handler === 'function') {
        handlers[action] = handler;
    }
    if (typeof originalSetActionHandler === 'function') {
        try { originalSetActionHandler.call(navigator.mediaSession, action, handler); } catch(e) {}
    }
};

Object.defineProperty(navigator.mediaSession, 'metadata', {
    get() { return _metadata; },
    set(value) {
        bgPlay(value);
        _metadata = value;
    },
    configurable: true
});

Object.defineProperty(navigator.mediaSession, 'playbackState', {
    get() { return _state; },
    set(value) {
        _state = value;
        var ytproAud = document.getElementsByClassName('video-stream')[0];

        if (value === 'playing') {
            setTimeout(() => { Android.bgPlay(ytproAud.currentTime * 1000); }, 100);
        } else if (value === 'paused' && (pauseAllowed || PIPause)) {
            setTimeout(() => { Android.bgPause(ytproAud.currentTime * 1000); }, 100);
        } else if (value === "none" && !(window.location.href.indexOf("youtube.com/watch") > -1 || window.location.href.indexOf("youtube.com/shorts") > -1)) {
            Android.bgStop();
            window.serviceRunning = false;
        }
    },
    configurable: true
});

}







async function bgPlay(info){


if(!(window.location.href.indexOf("youtube.com/watch") > -1 || window.location.href.indexOf("youtube.com/shorts") > -1 )) return;


if(!info) return;


var ytproAud = document.getElementsByClassName('video-stream')[0];


if(!ytproAud) return;


var iconBase64="iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+A8AAQUBAScY42YAAAAASUVORK5CYII=";


var img = new Image();
img.crossOrigin="anonymous";
img.src=info?.artwork?.[0]?.src;


var canvas = document.createElement('canvas');
canvas.style.width = "1600px"; 
canvas.style.height = "900px";
canvas.style.background="black";
var context = canvas.getContext('2d');

canvas.width = 160;
canvas.height  = 90;

//var z=performance.now();


await new Promise((res, rej) => {
    img.onload = () => res();
    img.onerror = () => res();
    setTimeout(() => res(), 2000);
});


try{
context.drawImage(img, 0,0 ,160,90);
iconBase64 = canvas.toDataURL('image/png',1.0);
}catch{}












if(window.serviceRunning){
setTimeout(()=>{Android.bgUpdate(iconBase64.replace("data:image/png;base64,", ""),info.title,info.artist,ytproAud.duration*1000);},50);
setTimeout(()=>{Android.bgPlay(ytproAud.currentTime*1000);},100);
}
else{
window.serviceRunning=true;
setTimeout(()=>{Android.bgStart(iconBase64.replace("data:image/png;base64,", ""),info.title,info.artist,ytproAud.duration*1000);},50);
setTimeout(()=>{Android.bgPlay(ytproAud.currentTime*1000);},100);
}











}


















/*When user hits the notification*/
function seekTo(t){
if (handlers.seekto) handlers.seekto({ seekTime: t/1000 });
}

/*Daamm , its play*/
function playVideo(){
if(!pauseAllowed){
window.PIPause = false;
navigator.mediaSession.playbackState = 'playing';
}
if (handlers.play) handlers.play();
}

/*Daamm , its pause*/
function pauseVideo(){
if(!pauseAllowed){
window.PIPause=true;
navigator.mediaSession.playbackState = 'paused';
}
if (handlers.pause) handlers.pause();
}



/*Alexa , play da next song*/
async function playNext(){
if (handlers.nexttrack) handlers.nexttrack();
}




/*Alexa , play the f**ng song once again */
function playPrev(){
if (handlers.previoustrack) handlers.previoustrack();
}
