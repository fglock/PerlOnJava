use strict;
use warnings;
use threads;
use re 'eval';
use Test::More tests => 2;

sub eval_match {
    my ($subject, $regex) = @_;
    my $matched;
    eval q{$matched = $subject =~ /$regex/ ? 1 : 0};
    return $matched || 0;
}

my $worker = threads->create(sub {
    my $trusted = 'Q';
    my $runtime = 'R';
    my @mixed = ('L', qr/(??{$trusted})/, '(??{$runtime})');
    my $first = eval_match('L Q R', qr/^@mixed$/);

    $trusted = 'S';
    $runtime = 'T';
    my $second = eval_match('L S T', qr/^@mixed$/);

    my $token = '[a-z]';
    my @parts = (qr/(??{$token})/);
    my $subject = 'a1b';
    pos($subject) = 1;
    my $failed = !($subject =~ /\G@parts/gc) && pos($subject) == 1;
    $token = '1';
    my $resumed = $subject =~ /\G@parts/gc
        && $& eq '1' && pos($subject) == 2;

    return join('|', $first, $second, $failed, $resumed);
});

is($worker->join, '1|1|1|1',
    'child mixed source retains cells and /gc state across eval reuse');
ok(eval_match('parent', qr/^parent$/),
    'parent eval regex remains usable after child-first execution');
