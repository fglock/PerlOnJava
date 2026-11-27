use strict;
use warnings;
use Scalar::Util qw(weaken);
use Test::More tests => 5;

our ($obj, $observer, $holder, $calls);
{
    package Ownership::WeakSlotResurrection;
    sub DESTROY {
        ++$main::calls;
        $main::holder->{slot} = $_[0] if $main::calls == 1;
    }
}

$obj = bless {}, 'Ownership::WeakSlotResurrection';
$holder = { slot => $obj };
$observer = $obj;
weaken($holder->{slot});
weaken($observer);

undef $obj;
is($calls, 1, 'DESTROY runs when the original owner leaves');
ok(defined($holder->{slot}), 'the weak aggregate slot is promoted during DESTROY');
ok(defined($observer), 'weak observers survive the rescue');

undef $holder->{slot};
is($calls, 2, 'releasing the promoted slot runs DESTROY again');
ok(!defined($observer), 'weak observers clear after final release');
