use strict;
use warnings;
use TOML qw(from_toml to_toml);

# Example TOML string
my $toml_string = <<'TOML';
# This is a TOML document

title = "TOML Example"

[owner]
name = "Tom Preston-Werner"
dob = 1979-05-27T07:32:00-08:00

[database]
enabled = true
ports = [ 8000, 8001, 8002 ]
data = [ ["delta", "phi"], [3.14] ]
temp_targets = { cpu = 79.5, case = 72.0 }

[servers]

[servers.alpha]
ip = "10.0.0.1"
role = "frontend"

[servers.beta]
ip = "10.0.0.2"
role = "backend"
TOML

print "=== Parsing TOML ===\n";
print "Input TOML:\n$toml_string\n";

# Parse with error checking (list context)
my ($data, $err) = from_toml($toml_string);
if ($err) {
    die "Error parsing TOML: $err\n";
}

print "=== Parsed Data ===\n";
print "Title: $data->{title}\n";
print "Owner name: $data->{owner}{name}\n";
print "Database enabled: $data->{database}{enabled}\n";
print "Database ports: ", join(", ", @{$data->{database}{ports}}), "\n";
print "Server alpha IP: $data->{servers}{alpha}{ip}\n";
print "Server beta role: $data->{servers}{beta}{role}\n";

# Parse in scalar context
print "\n=== Scalar Context Parsing ===\n";
my $data2 = from_toml($toml_string);
print "Title (scalar context): $data2->{title}\n";

# Create TOML from Perl data structure
print "\n=== Generating TOML ===\n";
my $perl_data = {
    title => "Generated TOML",
    author => {
        name => "John Doe",
        email => 'john@example.com'
    },
    settings => {
        debug => 1,  # Will be treated as integer
        version => 1.5,
        tags => ["perl", "toml", "example"]
    }
};

my $generated_toml = to_toml($perl_data);
print "Generated TOML:\n$generated_toml\n";

# Round-trip test
print "=== Round-trip Test ===\n";
my ($roundtrip_data, $roundtrip_err) = from_toml($generated_toml);
if ($roundtrip_err) {
    die "Round-trip error: $roundtrip_err\n";
}
print "Round-trip title: $roundtrip_data->{title}\n";
print "Round-trip author name: $roundtrip_data->{author}{name}\n";
print "Round-trip tags: ", join(", ", @{$roundtrip_data->{settings}{tags}}), "\n";

print "\n=== TOML parsing complete! ===\n";
