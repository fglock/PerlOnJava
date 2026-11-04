use strict;
use warnings;
use Scalar::Util qw(weaken);
use Test::More tests => 8;

package Local::ReentrantOverwrite;

sub new { bless { name => $_[1] }, $_[0] }

sub DESTROY {
    my ($self) = @_;
    my ($phase, $slot_kind) = split /-/, $self->{name}, 2;
    if ($phase eq 'old') {
        my $slot = $slot_kind eq 'scalar' ? $main::v
                : $slot_kind eq 'hash' ? $main::h->{slot}
                : $main::a->[0];
        $main::old_saw_replacement{$slot_kind} =
                ref($slot) eq __PACKAGE__ && $slot->{name} eq "new-$slot_kind";
        if ($slot_kind eq 'scalar') {
            Scalar::Util::weaken($main::v);
        } elsif ($slot_kind eq 'hash') {
            Scalar::Util::weaken($main::h->{slot});
        } else {
            Scalar::Util::weaken($main::a->[0]);
        }
    }
    push @main::destroyed, $self->{name};
}

package main;

our (@destroyed, %old_saw_replacement, $v, $h, $a);
$h = { slot => undef };
$a = [undef];

$v = Local::ReentrantOverwrite->new('old-scalar');
$v = Local::ReentrantOverwrite->new('new-scalar');
$h->{slot} = Local::ReentrantOverwrite->new('old-hash');
$h->{slot} = Local::ReentrantOverwrite->new('new-hash');
$a->[0] = Local::ReentrantOverwrite->new('old-array');
$a->[0] = Local::ReentrantOverwrite->new('new-array');

is_deeply(\@destroyed,
    [qw(old-scalar new-scalar old-hash new-hash old-array new-array)],
    'each old value is destroyed before its weakened replacement');
is_deeply([grep { /-scalar\z/ } @destroyed], [qw(old-scalar new-scalar)],
    'package scalar retains its RHS temporary through old destruction');
is_deeply([grep { /-hash\z/ } @destroyed], [qw(old-hash new-hash)],
    'hash element retains its RHS temporary through old destruction');
is_deeply([grep { /-array\z/ } @destroyed], [qw(old-array new-array)],
    'array element retains its RHS temporary through old destruction');
is_deeply(\%old_saw_replacement,
    { scalar => 1, hash => 1, array => 1 },
    'each destructor sees the replacement already installed in its slot');
ok(!defined($v), 'the scalar replacement stays weak after old destruction');
ok(!defined($h->{slot}), 'the hash element replacement stays weak after old destruction');
ok(!defined($a->[0]), 'the array element replacement stays weak after old destruction');
