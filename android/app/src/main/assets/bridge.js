// SpeedTracer Android アプリ用のつなぎ(ページより先に読み込まれる)
// - Web Bluetooth(navigator.bluetooth)を、アプリ側の Bluetooth でまねる(WebView には Web Bluetooth が無いため)
// - blob: / data: のファイル保存(CSV・ゴースト)を、端末の「ダウンロード」に保存する
(function(){
  if (window.__stAndroidBridge || !window.STAndroid) return;
  window.__stAndroidBridge = true;

  // ---------- Promise で待つ呼び出し ----------
  var pend = {}, seq = 0;
  function call(method, args){
    return new Promise(function(res, rej){
      var id = ++seq;
      pend[id] = {res: res, rej: rej};
      STAndroid.ble(method, JSON.stringify(args || {}), id);
    });
  }
  window.__stBleCb = function(id, ok, val){
    var p = pend[id]; if (!p) return; delete pend[id];
    if (ok) p.res(val); else { var e = new Error(val || 'Bluetooth エラー'); e.name = 'NetworkError'; p.rej(e); }
  };

  // ---------- イベントの簡易実装 ----------
  function Target(){ this._ls = {}; }
  Target.prototype.addEventListener = function(t, f){ (this._ls[t] = this._ls[t] || []).push(f); };
  Target.prototype.removeEventListener = function(t, f){ var a = this._ls[t]; if (a) this._ls[t] = a.filter(function(x){ return x !== f; }); };
  Target.prototype._fire = function(t, ev){
    ev = ev || {}; ev.type = t; if (!ev.target) ev.target = this;
    (this._ls[t] || []).slice().forEach(function(f){ try { f.call(ev.target, ev); } catch (e) { console.error(e); } });
    var h = this['on' + t]; if (typeof h === 'function') { try { h.call(ev.target, ev); } catch (e) {} }
  };

  var chars = {};
  function Characteristic(service, uuid){ Target.call(this); this.service = service; this.uuid = uuid; this.value = null; }
  Characteristic.prototype = Object.create(Target.prototype);
  Characteristic.prototype.startNotifications = function(){ var self = this; return call('notify', {uuid: this.uuid}).then(function(){ return self; }); };
  Characteristic.prototype.stopNotifications = function(){ return Promise.resolve(this); };
  function toB64(buf){
    var u8 = buf instanceof ArrayBuffer ? new Uint8Array(buf) : new Uint8Array(buf.buffer, buf.byteOffset || 0, buf.byteLength);
    var s = ''; for (var i = 0; i < u8.length; i++) s += String.fromCharCode(u8[i]); return btoa(s);
  }
  Characteristic.prototype.writeValue = function(buf){ return call('write', {uuid: this.uuid, b64: toB64(buf)}).then(function(){}); };
  Characteristic.prototype.writeValueWithResponse = Characteristic.prototype.writeValue;
  Characteristic.prototype.writeValueWithoutResponse = Characteristic.prototype.writeValue;
  Characteristic.prototype.readValue = function(){ return Promise.reject(new Error('readValue は未対応です')); };

  function Service(server, uuid){ this.device = server.device; this.uuid = uuid; this.isPrimary = true; }
  Service.prototype.getCharacteristic = function(uuid){
    var self = this; uuid = String(uuid).toLowerCase();
    return call('hasChar', {service: this.uuid, uuid: uuid}).then(function(){
      var c = chars[uuid] || (chars[uuid] = new Characteristic(self, uuid)); return c;
    });
  };

  var device = null;
  function Device(r){
    Target.call(this);
    this.id = r.id; this.name = r.name || null;
    var dev = this;
    this.gatt = {
      device: dev, connected: false,
      connect: function(){ var g = this; return call('connect', {id: dev.id}).then(function(){ g.connected = true; return g; }); },
      disconnect: function(){ this.connected = false; STAndroid.ble('disconnect', '{}', 0); },
      getPrimaryService: function(uuid){
        var g = this; uuid = String(uuid).toLowerCase();
        return call('hasService', {uuid: uuid}).then(function(){ return new Service(g, uuid); });
      }
    };
  }
  Device.prototype = Object.create(Target.prototype);

  window.__stBleNotify = function(uuid, b64){
    var c = chars[uuid]; if (!c) return;
    var s = atob(b64), u8 = new Uint8Array(s.length);
    for (var i = 0; i < s.length; i++) u8[i] = s.charCodeAt(i);
    c.value = new DataView(u8.buffer);
    c._fire('characteristicvaluechanged', {target: c});
  };
  window.__stBleDisconnected = function(){
    if (!device) return;
    device.gatt.connected = false;
    device._fire('gattserverdisconnected', {target: device});
  };

  var bluetooth = {
    requestDevice: function(opts){
      return call('requestDevice', opts || {}).then(function(r){
        chars = {}; device = new Device(r); return device;
      });
    },
    getAvailability: function(){ return Promise.resolve(STAndroid.bleAvailable()); }
  };
  try { Object.defineProperty(Navigator.prototype, 'bluetooth', {get: function(){ return bluetooth; }, configurable: true}); }
  catch (e) { try { navigator.bluetooth = bluetooth; } catch (e2) {} }

  // ---------- ファイル保存(a[download] のクリック) ----------
  var origClick = HTMLAnchorElement.prototype.click;
  HTMLAnchorElement.prototype.click = function(){
    var href = this.href || '', name = this.getAttribute('download');
    if (name != null && (href.indexOf('blob:') === 0 || href.indexOf('data:') === 0)) {
      fetch(href).then(function(r){ return r.blob(); }).then(function(b){
        var fr = new FileReader();
        fr.onload = function(){
          var s = String(fr.result), i = s.indexOf(',');
          STAndroid.saveFile(name || 'download', b.type || 'application/octet-stream', s.slice(i + 1));
        };
        fr.readAsDataURL(b);
      }).catch(function(e){ STAndroid.toast('保存できませんでした: ' + e.message); });
      return;
    }
    return origClick.apply(this, arguments);
  };
})();
