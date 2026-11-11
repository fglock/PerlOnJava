use strict;
use warnings;
use Test::More;

eval { require IPC::Run3; 1 } or plan skip_all => 'IPC::Run3 is not installed';
plan tests => 2;

my $input = "spooled input\n";
my $actual = '';
my $child = q{my $input = do { local $/; <STDIN> }; print $input;};

my $status = IPC::Run3::run3(
    [$^X, '-e', $child],
    \$input,
    \$actual,
    \undef,
);
ok($status, 'IPC::Run3 child exits successfully after scalar input is spooled');
is($actual, $input, 'IPC::Run3 child receives the complete spooled input');
