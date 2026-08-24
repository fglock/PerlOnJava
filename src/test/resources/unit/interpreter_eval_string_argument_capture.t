use strict;
use warnings;
use Test::More tests => 9;
use Scalar::Util qw(refaddr);

sub clean_eval { eval $_[0] }

my $anon_slot;
my $anon_slot_ref = \$anon_slot;
my %anon_captures = ('$slot' => \$anon_slot_ref);
my $anon_success = clean_eval(<<'CODE', \%anon_captures);
my $slot = ${$_[1]->{'$slot'}};
$$slot = sub { 21 };
1;
CODE

is($anon_success, 1, 'capture-bearing anonymous-sub eval succeeds');
is(ref($anon_slot), 'CODE', 'eval publishes anonymous CODE through captured argument');
is($anon_slot->(), 21, 'published anonymous CODE runs');

sub InterpreterEvalArgumentCapture::named { 'outer' }
my $named_slot;
my $named_slot_ref = \$named_slot;
my %named_captures = ('$slot' => \$named_slot_ref);
my @warnings;
my $named_success;
{
    no strict 'refs';
    local *InterpreterEvalArgumentCapture::named;
    local $SIG{__WARN__} = sub { push @warnings, @_ };
    $named_success = clean_eval(<<'CODE', \%named_captures);
{
  my $slot = ${$_[1]->{'$slot'}};
  package InterpreterEvalArgumentCapture;
  sub named { 42 }
  $$slot = \&named;
}
1;
CODE
    is($named_success, 1, 'capture-bearing named-sub eval succeeds');
    is(ref($named_slot), 'CODE', 'eval publishes named CODE through captured argument');
    is($named_slot->(), 42, 'published named CODE runs while glob is localized');
    my $installed = *{'InterpreterEvalArgumentCapture::named'}{CODE};
    is(refaddr($named_slot), refaddr($installed),
        'published CODE matches localized glob CODE');
}
is_deeply(\@warnings, [], 'localized glob prevents redefine warning');
is(InterpreterEvalArgumentCapture::named(), 'outer', 'outer glob CODE is restored');
