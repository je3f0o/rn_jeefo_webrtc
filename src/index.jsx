import {useState}   from 'react';
import EventEmitter from '@jeefo/utils/event_emitter';
import {
  View,
  NativeModules,
  DeviceEventEmitter,
  requireNativeComponent,
} from "react-native";

export const {RTCModule} = NativeModules;

const RTCViewNative = requireNativeComponent("RCTRTCView");

class VideoRoomPlugin extends EventEmitter {
  constructor() {
    super();
  }

  join(room, username) {
    RTCModule.pluginMethod("video_room", "join", {room, username});
  }

  destroy() {
  }
}

class Signaller extends EventEmitter {
  constructor() {
    super();
    this.plugins = {};

    DeviceEventEmitter.addListener("event", e => {
      e = JSON.parse(e);
      const [plugin, event_type] = e.type.split('.');
      if (event_type) {
        e.type = event_type;
        this.plugins[plugin].emit("event", e);
        return;
      }

      switch (e.type) {
        case "attached":
          if (this.plugins[e.name]) {
            return this.emit("event", {
              type    : "error",
              message : `'${e.name}' plugin already attached...`,
            });
          }
          if (e.name === "video_room") {
            e.plugin = new VideoRoomPlugin();
            this.plugins[e.name] = e.plugin;
          }
          this.emit("event", e);
          break;
        default: this.emit("event", e);
      }
    });
  }

  init(url) {
    RTCModule.init(url);
  }

  attach(plugin_name) {
    RTCModule.attach(plugin_name);
  }

  destroy() {
    return RTCModule.destroy();
  }
}

export const signaller = new Signaller();

export const RTCView = properties => {
  const [side, set_side] = useState("front");
  const [style, set_style] = useState({width: 0, height: 0});
  const props = Object.assign({}, properties);
  const {feedName} = props;
  delete props.feedName;

  props.style = {};
  if (properties.style) Object.assign(props.style, properties.style)
  props.style.backgroundColor = "black";

  let raf_id;
  DeviceEventEmitter.addListener("update_view", peer_name => {
    if (feedName !== peer_name) return;

    set_style({width: '100%', height: '100%', paddingBot: 1});

    cancelAnimationFrame(raf_id);
    raf_id = requestAnimationFrame(() => {
      set_style({width: '100%', height: '100%'});
    });
    set_side("back");
  });

  //setInterval(() => {
  //  set_side(s => s === "back" ? "front" : "back");
  //}, 5000);

  return (
    <View {...props}>
      <RTCViewNative feedName={feedName} style={style} />
    </View>
  );
};