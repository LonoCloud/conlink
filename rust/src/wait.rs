// Copyright (c) 2024, Equinix, Inc
// Licensed under MPL 2.0

use std::env;
use std::time::Duration;
use std::thread::sleep;
use std::process::Command;
use std::fs;
use std::net::TcpStream;
use std::os::unix::process::CommandExt;
use std::io::Read;

const USAGE: &str = "Usage: wait [OPTIONS] [-- COMMAND]

Options:
  -f, --file FILE          Wait until the specified file exists
  -i, --if, --intf IFACE   Wait until the specified network interface exists
  -I, --ip IFACE           Wait until the specified interface has IP/routing
  -t, --tcp HOST:PORT      Wait until the specified TCP host:port is reachable
  -c, --cmd COMMAND        Wait until the specified command succeeds
  -s, --sleep SECONDS      Set the sleep duration between retries (default: 1)
  --                       Separate wait options from the command to run";

fn main() {
    let mut args = env::args().skip(1); // Skip the program name
    let mut sleep_duration = 1; // Default sleep duration

    eprintln!("Starting wait...");

    while let Some(arg) = args.next() {
        match arg.as_str() {
            "--" => {
                // Collect the remaining arguments as the command to exec
                let command = args.collect::<Vec<String>>();

                if !command.is_empty() {
                    if !which::which(&command[0]).is_ok() {
                        eprintln!("Error: '{}' not found", command[0]);
                        std::process::exit(1);
                    }
                    // Exec the command, replacing the current process
                    Command::new(&command[0])
                        .args(&command[1..])
                        .exec(); // This replaces the current process and doesn't return
                }
                return; // If no command is provided after '--', exit
            }
            "-f" | "--file" => {
                if let Some(file) = args.next() {
                    wait_for_file(&file, sleep_duration);
                } else {
                    eprintln!("--file requires a file path argument");
                    return;
                }
            }
            "-i" | "--if" | "--intf" => {
                if let Some(interface) = args.next() {
                    wait_for_interface(&interface, sleep_duration);
                } else {
                    eprintln!("--if requires an interface name argument");
                    return;
                }
            }
            "-I" | "--ip" => {
                if let Some(interface) = args.next() {
                    wait_for_ip_routing(&interface, sleep_duration);
                } else {
                    eprintln!("--ip requires an interface name argument");
                    return;
                }
            }
            "-t" | "--tcp" => {
                if let Some(tcp) = args.next() {
                    let parts: Vec<&str> = tcp.split(':').collect();
                    let host = parts[0];
                    let port: u16 = parts[1].parse().expect("Invalid port number");
                    wait_for_tcp(host, port, sleep_duration);
                } else {
                    eprintln!("--tcp requires a host:port argument");
                    return;
                }
            }
            "-c" | "--cmd" | "--command" => {
                if let Some(command) = args.next() {
                    wait_for_command(&command, sleep_duration);
                } else {
                    eprintln!("--command requires a command string argument");
                    return;
                }
            }
            "-s" | "--sleep" => {
                if let Some(sleep_str) = args.next() {
                    if let Ok(duration) = sleep_str.parse::<u64>() {
                        sleep_duration = duration;
                    } else {
                        eprintln!("Invalid sleep duration");
                        return;
                    }
                } else {
                    eprintln!("--sleep requires a duration in seconds");
                    return;
                }
            }
            _ => {
                eprintln!("Unknown option: {}\n{}", arg, USAGE);
                return;
            }
        }
    }
}

fn wait_for_file(path: &str, sleep_duration: u64) {
    while !fs::metadata(path).is_ok() {
        println!("Waiting for file: {}...", path);
        sleep(Duration::from_secs(sleep_duration));
    }
    println!("File '{}' exists", path);
}

fn wait_for_interface(interface: &str, sleep_duration: u64) {
    let path = format!("/sys/class/net/{}", interface);
    while !fs::metadata(&path).is_ok() {
        println!("Waiting for interface: {}...", interface);
        sleep(Duration::from_secs(sleep_duration));
    }
    println!("Interface '{}' exists", interface);
}

fn wait_for_ip_routing(interface: &str, sleep_duration: u64) {
    let mut content = String::new();
    while fs::File::open("/proc/net/route")
        .and_then(|mut f| f.read_to_string(&mut content))
        .map(|_| content.lines().any(|line| line.starts_with(interface) && line[interface.len()..]
                                     .starts_with(|c| c == '\t' || c == ' ')))
        .unwrap_or(false) == false
    {
        println!("Waiting for IP/routing on interface: {}...", interface);
        content.clear();
        sleep(Duration::from_secs(sleep_duration));
    }
    println!("Interface '{}' has IP/routing", interface);
}

fn wait_for_tcp(host: &str, port: u16, sleep_duration: u64) {
    while TcpStream::connect((host, port)).is_err() {
        println!("Waiting for TCP connection at {}:{}", host, port);
        sleep(Duration::from_secs(sleep_duration));
    }
    println!("TCP connection established at {}:{}", host, port);
}

fn wait_for_command(command: &str, sleep_duration: u64) {
    while Command::new("sh").arg("-c").arg(command).status().unwrap().success() == false {
        println!("Command '{}' failed, retrying...", command);
        sleep(Duration::from_secs(sleep_duration));
    }
    println!("Command '{}' executed successfully", command);
}
