use strict;
use warnings;
use JSON;

# Simple and fast interfaces (expect/generate UTF-8)
my $perl_hashref = { name => 'John Doe', age => 30, email => 'john.doe@example.com' };
my $utf8_encoded_json_text = encode_json($perl_hashref);
print "Encoded JSON (simple interface): $utf8_encoded_json_text\n";

my $decoded_perl_hashref = decode_json($utf8_encoded_json_text);
print "Decoded Perl hash (simple interface):\n";
print "Name: $decoded_perl_hashref->{name}\n";
print "Age: $decoded_perl_hashref->{age}\n";
print "Email: $decoded_perl_hashref->{email}\n";

# OO-interface
my $json = JSON->new->allow_nonref;

my $perl_scalar = { city => 'New York', country => 'USA' };
my $json_text = $json->encode($perl_scalar);
print "Encoded JSON (OO interface): $json_text\n";

my $decoded_perl_scalar = $json->decode($json_text);
print "Decoded Perl scalar (OO interface):\n";
print "City: $decoded_perl_scalar->{city}\n";
print "Country: $decoded_perl_scalar->{country}\n";

# Pretty-printing
my $pretty_printed = $json->pretty->encode($perl_scalar);
print "Pretty-printed JSON:\n$pretty_printed\n";

