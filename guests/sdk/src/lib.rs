//! Bindings for `pumpkin:client/client-mod@0.1.0`, shared by the sample client components.

wit_bindgen::generate!({
    path: "../../wit/src/main/resources/wit/client-mod.wit",
    world: "pumpkin:client/client-mod@0.1.0",
    generate_all,
    pub_export_macro: true,
    default_bindings_module: "pumpkin_client_sdk",
});

pub use exports::pumpkin::client::guest::{
    self, ActionEvent, DrawCommand, Event, EventKinds, FrameInfo, FrameOutput, InitInfo, InitResult,
    RectCmd, TextCmd,
};
pub use pumpkin::client::{hud, log, net, view};

/// Logs at info level.
pub fn info(message: &str) {
    log::log(log::Level::Info, message);
}
