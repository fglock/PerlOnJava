use strict;
use warnings;
use Test::More tests => 19;

use_ok('TOML', qw(from_toml to_toml));

# Test basic parsing
{
    my $toml = 'key = "value"';
    my ($data, $err) = from_toml($toml);
    ok(!$err, 'no error on simple parse');
    is($data->{key}, 'value', 'simple key-value parsed');
}

# Test integer parsing
{
    my $toml = 'number = 42';
    my ($data, $err) = from_toml($toml);
    ok(!$err, 'no error on integer parse');
    is($data->{number}, 42, 'integer parsed correctly');
}

# Test float parsing
{
    my $toml = 'pi = 3.14159';
    my ($data, $err) = from_toml($toml);
    ok(!$err, 'no error on float parse');
    ok(abs($data->{pi} - 3.14159) < 0.0001, 'float parsed correctly');
}

# Test boolean parsing
{
    my $toml = "enabled = true\ndisabled = false";
    my ($data, $err) = from_toml($toml);
    ok(!$err, 'no error on boolean parse');
    ok($data->{enabled}, 'true parsed correctly');
    ok(!$data->{disabled}, 'false parsed correctly');
}

# Test array parsing
{
    my $toml = 'items = [1, 2, 3]';
    my ($data, $err) = from_toml($toml);
    ok(!$err, 'no error on array parse');
    is_deeply($data->{items}, [1, 2, 3], 'array parsed correctly');
}

# Test nested table parsing
{
    my $toml = "[section]\nkey = \"value\"";
    my ($data, $err) = from_toml($toml);
    ok(!$err, 'no error on table parse');
    is($data->{section}{key}, 'value', 'nested table parsed correctly');
}

# Test to_toml
{
    my $data = { name => "test", count => 5 };
    my $toml = to_toml($data);
    ok($toml =~ /name = "test"/, 'to_toml generates string value');
    ok($toml =~ /count = 5/, 'to_toml generates integer value');
}

# Test round-trip
{
    my $original = { title => "Round Trip", values => [1, 2, 3] };
    my $toml = to_toml($original);
    my ($parsed, $err) = from_toml($toml);
    ok(!$err, 'no error on round-trip');
    is($parsed->{title}, $original->{title}, 'round-trip preserves string');
    is_deeply($parsed->{values}, $original->{values}, 'round-trip preserves array');
}
