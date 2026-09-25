use strict;
use warnings;
use File::Temp qw(tempfile);
use Test::More;

use YAML::Syck qw(Load Dump LoadFile DumpFile);

my $value = Load("---\nanswer: 42\n");
is($value->{answer}, 42, 'YAML::Syck exports Load');

my $yaml = Dump({answer => 42});
like($yaml, qr/answer:\s*42/, 'YAML::Syck exports Dump');

my $legacy = Load("---\nEVAL_REPLACE:\n  %TOP%: \t%DATADIR%/config.yml\n");
is($legacy->{EVAL_REPLACE}{'%TOP%'}, '%DATADIR%/config.yml',
    'Load accepts YAML::Syck percent-leading template scalars and tabs');

my $duplicate = Load("---\nsources:\n  - name: first\n    name: second\n");
is($duplicate->{sources}[0]{name}, 'second',
    'Load preserves YAML::Syck last-value duplicate-key behavior');

my $sequence = Load("---\ninclude:\n  - %TOP%/config.yml\n");
is($sequence->{include}[0], '%TOP%/config.yml',
    'Load accepts percent-leading YAML::Syck sequence scalars');

my @documents = Load("---\none: 1\n---\ntwo: 2\n");
is_deeply(\@documents, [{one => 1}, {two => 2}], 'Load returns all YAML documents in list context');

my ($fh, $path) = tempfile();
close $fh or die "close tempfile: $!";
DumpFile($path, {from_file => 'yes'});
is(LoadFile($path)->{from_file}, 'yes', 'DumpFile and LoadFile round trip');
unlink $path or die "unlink $path: $!";

done_testing;
