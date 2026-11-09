// sqlx::migrate! embeds the migrations at compile time; a new file must trigger a rebuild
fn main() {
    println!("cargo:rerun-if-changed=migrations");
}
