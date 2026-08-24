use strict;
use warnings;
use v5.16;
use utf8;
use charnames ':full';
use re 'eval';
use Test::More;
use lib 'src/test/resources/unit/lib';

our ($forward, $forward_negative, $mode_sensitive, $mode_folded, $retry_boom,
    $with_callout, $callout_count, $stateful_charname, $gc_runner);
our ($order_im, $order_mi);
{
    use RegexImplementationCname;
    BEGIN {
        $RegexImplementationCname::Evil = 'A';
        $stateful_charname = eval q{qr/^\N{EVIL}\p{InCacheKana}$/};
        die $@ if $@;
    }
}
BEGIN {
    $forward = qr/\p{InCacheKana}/;
    $forward_negative = qr/\P{InCacheKana}/;
    $mode_sensitive = qr/\p{InCacheMode}/;
    $mode_folded = qr/\p{InCacheMode}/i;
    $retry_boom = qr/\p{InCacheRetryBoom}/;
    $with_callout = qr/(?{ ++$main::callout_count })\p{InCacheKana}/;
    $order_im = qr/\p{InCacheOrder}/im;
    $order_mi = qr/\p{InCacheOrder}/mi;
    $gc_runner = eval q{sub {
        my $subject = "\x{3040}x";
        pos($subject) = 0;
        my @events;
        for (1 .. 2) {
            my $matched = $subject =~ /\p{InCacheGc}/gc ? 1 : 0;
            push @events, $matched, pos($subject);
        }
        return \@events;
    }};
    die $@ if $@;
}

sub InCacheKana { "3040\t309f\n30a0\t30ff\n" }
sub InCacheMode { $_[0] ? "0200\n" : "0100\n" }
sub InCacheRetryBoom { die "cache retry boom\n" }
sub InCacheGc { "3040\n" }
sub InCacheOrder { "0500\n" }
sub CacheFixture::InScoped { "0400\n" }

is($RegexImplementationCname::Evil, 'AB',
    'custom charname translator runs once during initial construction');
ok("A\x{3040}" =~ $stateful_charname,
    'deferred recompilation preserves the original named expansion');
is($RegexImplementationCname::Evil, 'AB',
    'deferred recompilation does not rerun the custom charname translator');

ok("\x{3040}" =~ $forward, 'forward-deferred property resolves at first use');
ok("\x{303f}" =~ $forward_negative, 'forward-deferred complement resolves at first use');

my $fresh_positive = eval q{qr/\p{InCacheKana}/};
is($@, '', 'fresh positive property compiles after deferred resolution');
ok("\x{3040}" =~ $fresh_positive, 'fresh positive property uses resolved cache');
ok("\x{303f}" !~ $fresh_positive, 'fresh positive property does not reuse placeholder');

my $fresh_negative = eval q{qr/\P{InCacheKana}/};
is($@, '', 'fresh negative property compiles after deferred resolution');
ok("\x{303f}" =~ $fresh_negative, 'fresh negative property uses resolved cache');
ok("\x{3040}" !~ $fresh_negative, 'fresh negative property excludes resolved member');

ok("\x{0100}" =~ $mode_sensitive, 'sensitive callback mode keeps its own cache entry');
ok("\x{0200}" !~ $mode_sensitive, 'sensitive callback mode excludes folded-only member');
ok("\x{0200}" =~ $mode_folded, 'folded callback mode keeps its own cache entry');
ok("\x{0100}" !~ $mode_folded, 'folded callback mode excludes sensitive-only member');

my $qualified = eval q{qr/\p{CacheFixture::InScoped}/};
is($@, '', 'qualified user property compiles');
ok("\x{0400}" =~ $qualified, 'qualified user property matches its member');
ok("\x{0401}" !~ $qualified, 'qualified user property excludes adjacent scalar');

my @patterns = ($fresh_positive, $fresh_negative, $qualified);
sub nested_match { $_[0] =~ $_[1] }
ok(nested_match("\x{3040}", $patterns[0]), 'array-held regex survives nested call');
ok(nested_match("\x{303f}", $patterns[1]), 'array-held complement survives nested call');

my $global = "\x{3040}\x{3041}";
my @global_matches = $global =~ /($fresh_positive)/g;
is_deeply(\@global_matches, ["\x{3040}", "\x{3041}"],
    'global matching reuses resolved compiled property');

$callout_count = 0;
ok("\x{3040}" =~ $with_callout,
    'deferred property recompilation preserves executable callouts');
is($callout_count, 1, 'preserved callout executes exactly once');

is_deeply($gc_runner->(), [1, 1, 0, 1],
    'deferred literal global match preserves pos after gc failure');

ok("\x{0500}" =~ $order_im,
    'first raw modifier order resolves without another placeholder cache hit');
ok("\x{0501}" !~ $order_im,
    'first raw modifier order preserves nonmembership');
ok("\x{0500}" =~ $order_mi,
    'equivalent second modifier order resolves independently');
ok("\x{0501}" !~ $order_mi,
    'equivalent second modifier order preserves nonmembership');

for my $attempt (1 .. 2) {
    my $matched = eval { "A" =~ $retry_boom; 1 };
    ok(!$matched, "failed deferred callback remains fatal on attempt $attempt");
    like($@, qr/cache retry boom/, "failed deferred callback error repeats on attempt $attempt");
}

done_testing;
