#!perl -T
use strict;
use warnings;
use Test::More;

my $tainted_source = '1' . substr($0, 0, 0);

sub render_tainted_source {
    my $result = eval $tainted_source;
    return $@ ? 'template error' : $result;
}

my $completed = eval {
    render_tainted_source();
    1;
};

ok(!defined($completed),
    'tainted eval security error propagates past the attempted eval');
like($@, qr/^Insecure dependency in eval while running with -T switch/,
    'outer eval receives the taint security error');

done_testing();
