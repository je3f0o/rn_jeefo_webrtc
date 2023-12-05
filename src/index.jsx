import {useState} from 'react';
import {
  View,
  NativeModules,
  DeviceEventEmitter,
  requireNativeComponent,
} from "react-native";

export const {RTCModule} = NativeModules;

const RTCViewNative = requireNativeComponent("RCTRTCView");

export const RTCView = properties => {
  const [side, set_side] = useState("front");
  const [style, set_style] = useState({width: 0, height: 0});
  const props = Object.assign({}, properties);
  const {peerName} = props;
  delete props.peerName;

  props.style = {};
  if (properties.style) Object.assign(props.style, properties.style)
  props.style.backgroundColor = "black";

  let raf_id;
  DeviceEventEmitter.addListener("update_view", peer_name => {
    if (peerName !== peer_name) return;

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
      <RTCViewNative peerName={peerName} style={style} />
    </View>
  );
};