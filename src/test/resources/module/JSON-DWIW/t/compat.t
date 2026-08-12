use strict;
use warnings;
use Test::More;
use File::Temp qw(tempfile);
use JSON::DWIW qw(from_json deserialize_json);

my $json = JSON::DWIW->new;
my $data = $json->from_json(q({ bare: 'value', list: [1,2], yes: true, no: null }));
is($data->{bare}, 'value', 'accepts bare keys and single quotes');
is_deeply($data->{list}, [1, 2], 'accepts relaxed object syntax');
ok($data->{yes}, 'decodes true');
ok(exists($data->{no}) && !defined($data->{no}), 'decodes null');

my $encoded = $json->to_json({ alpha => [1, 2], enabled => JSON::DWIW->true });
my $roundtrip = JSON::DWIW::deserialize($encoded);
is_deeply($roundtrip->{alpha}, [1, 2], 'round trips nested data');
ok($roundtrip->{enabled}, 'round trips explicit boolean');

my $bare = JSON::DWIW->to_json({ alpha => 1 }, { bare_keys => 1 });
like($bare, qr/alpha\s*:/, 'bare_keys affects encoding');

my ($bad, $error) = JSON::DWIW->from_json('{ bad: ]');
ok(!defined($bad) && defined($error), 'parse errors are returned in list context');
like(JSON::DWIW->get_error_string, qr/JSON::DWIW/, 'last parse error is retained');

my ($fh, $file) = tempfile();
print {$fh} q({file_value: 42});
close $fh;
is(JSON::DWIW->from_json_file($file)->{file_value}, 42, 'decodes a file');

ok(JSON::DWIW->has_deserialize, 'deserialize backend is available');
ok(JSON::DWIW->have_big_int, 'Math::BigInt support is available');
is(ord(JSON::DWIW->code_point_to_utf8_str(0xE9)), 0xE9,
    'encodes a Unicode code point');

done_testing;
