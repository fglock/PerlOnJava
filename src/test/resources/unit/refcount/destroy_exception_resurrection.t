use strict;
use warnings;
use Scalar::Util qw(weaken);
use Test::More tests => 11;

our ($saved, $calls, $warnings);
our @reblessed;
{
    package Local::DyingResurrector;
    sub DESTROY {
        ++$main::calls;
        $main::saved = $_[0] if $main::calls == 1;
        die "dying after rescue\n";
    }
}
{
    package Local::DyingRebless;
    sub DESTROY {
        push @main::reblessed, 'original';
        bless $_[0], 'Local::AfterDyingRebless';
        die "dying after rebless\n";
    }
}
{
    package Local::AfterDyingRebless;
    sub DESTROY { push @main::reblessed, 'replacement' }
}

local $SIG{__WARN__} = sub { $warnings .= $_[0] };
my $value = bless {}, 'Local::DyingResurrector';
my $weak = $value;
weaken($weak);
$@ = 'caller state';
$value = undef;
ok(defined($saved), 'a dying destructor can publish a strong owner');
ok(defined($weak), 'weak observers survive real resurrection');
is($calls, 1, 'the initial destructor runs once');
$saved = undef;
ok(!defined($weak), 'the final resurrected owner clears weak observers');
is($calls, 2, 'releasing resurrection invokes DESTROY again');
is($warnings, "\t(in cleanup) dying after rescue\n" x 2,
    'both dying destructor calls report cleanup warnings');
is($@, 'caller state', 'destruction preserves the caller exception state');

my $reblessed = bless {}, 'Local::DyingRebless';
my $weak_reblessed = $reblessed;
weaken($weak_reblessed);
$reblessed = undef;
is_deeply(\@reblessed, [qw(original replacement)],
    'a dying destructor still dispatches destruction for its new class');
ok(!defined($weak_reblessed), 'reblessed destruction clears weak observers');
is($warnings, ("\t(in cleanup) dying after rescue\n" x 2)
        . "\t(in cleanup) dying after rebless\n",
    'reblessing reports the original cleanup exception once');
is($@, 'caller state', 'reblessed destruction preserves the caller exception state');
