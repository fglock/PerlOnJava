use strict;
use warnings;
use Test::More tests => 2;

package Issue1649::CapturedCellOld;
sub new { bless { name => $_[1] }, $_[0] }
sub DESTROY {
    my ($self) = @_;
    return unless $self->{name} eq 'old';
    my $current = $main::reader->();
    $main::observed_capture = $current->{name};
}

package Issue1649::CapturedCellNew;
sub new { bless { name => $_[1] }, $_[0] }

package main;
our ($reader, $observed_capture);
my $captured = Issue1649::CapturedCellOld->new('old');
$reader = sub { $captured };
$captured = Issue1649::CapturedCellNew->new('new');

is($observed_capture, 'new',
    'the displaced capture value is destroyed after the captured cell is updated');
is($reader->()->{name}, 'new', 'the closure reads the replacement value');
