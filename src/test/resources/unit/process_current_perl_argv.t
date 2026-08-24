use strict;
use warnings;
use Test::More;

use lib 'src/main/perl/lib';
use PerlOnJava::Process qw(run_process);

my @payload = (
    'alpha beta',
    'double"quote',
    "single'quote",
    '$dollar',
    'semi;pipe|data',
);
my $program = 'print join qq(\x1e), map { length($_) . q(:) . $_ } @ARGV';
my $result = run_process(
    argv => [$^X, '-e', $program, @payload],
    timeout => 10,
);

is $result->{exit_code}, 0,
    'the current Perl executable can be relaunched with argv semantics';
is $result->{output},
    join("\x1e", map { length($_) . ':' . $_ } @payload),
    'the relaunched interpreter receives every argument without shell parsing';
ok !$result->{timed_out}, 'the current-interpreter child completes before timeout';

done_testing;
