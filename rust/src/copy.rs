// Copyright (c) 2024, Equinix, Inc
// Licensed under MPL 2.0

use anyhow::{Context, Result};
use regex::Regex;
use std::{
    env,
    fs::{self, File},
    io::{Read, Write},
    path::{PathBuf},
    process::Command,
};
use structopt::StructOpt;

#[derive(Debug, StructOpt)]
#[structopt(name = "copy", about = "Recursively copy with optional templating")]
#[structopt(usage = "copy [-T|--template] SRC_DIR DST_DIR [-- COMMAND...]")]
struct Opt {
    #[structopt(short = "T", long = "template",
                help = "Enable variable substitution")]
    template: bool,

    #[structopt(parse(from_os_str), help = "Source directory")]
    src_dir: PathBuf,

    #[structopt(parse(from_os_str), help = "Destination directory")]
    dst_dir: PathBuf,

    #[structopt(name = "COMMAND", last = true, allow_hyphen_values = true,
                help = "Optional command to run after copy")]
    command: Vec<String>,
}

fn main() -> Result<()> {
    let opt = Opt::from_args();

    if !opt.src_dir.is_dir() {
        anyhow::bail!("Not a directory: '{}'", opt.src_dir.display());
    }
    if !opt.dst_dir.is_dir() {
        anyhow::bail!("Not a directory: '{}'", opt.dst_dir.display());
    }

    // Walk through source directory
    for entry in walkdir::WalkDir::new(&opt.src_dir)
        .into_iter()
        .filter_map(|e| e.ok())
        .filter(|e| e.file_type().is_file())
    {
        let rel_path = entry
            .path()
            .strip_prefix(&opt.src_dir)
            .context("Failed to strip prefix")?;
        let dst_path = opt.dst_dir.join(rel_path);

        // Create parent directories
        if let Some(parent) = dst_path.parent() {
            fs::create_dir_all(parent).context("Failed to create target directory")?;
        }

        // Get source permissions
        let src_permissions = entry.metadata()?.permissions();

        // Read source file
        let mut content = String::new();
        File::open(entry.path())?.read_to_string(&mut content)?;

        println!("Copying '{}' to '{}'", entry.path().display(), dst_path.display());

        // Process template if requested
        if opt.template {
            let re = Regex::new(r"\{\{([^}\s]+)\}\}")?;
            for cap in re.captures_iter(&content.clone()) {
                let var_name = &cap[1];
                if let Ok(var_value) = env::var(var_name) {
                    println!(r"Replacing '{{{{{}}}}}' with '{}' in '{}'",
                             var_name, var_value, dst_path.display());
                    content = content.replace(&format!(r"{{{{{}}}}}", var_name),
                                              &var_value);
                }
            }
        }

        // Write content to destination
        File::create(&dst_path)
            .context("Failed to create destination file")?
            .write_all(content.as_bytes())
            .context("Failed to write file content")?;

        // Set permissions to match source
        fs::set_permissions(&dst_path, src_permissions)
            .context("Failed to set permissions")?;
    }

    // Execute command if provided
    if !opt.command.is_empty() {
        println!("Running: {:?}", opt.command);
        let status = Command::new(&opt.command[0])
            .args(&opt.command[1..])
            .status()
            .context("Failed to execute command")?;
        std::process::exit(status.code().unwrap_or(1));
    }

    Ok(())
}
