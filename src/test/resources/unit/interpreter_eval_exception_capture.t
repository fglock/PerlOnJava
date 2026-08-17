use strict;
use warnings;
use Test::More tests => 3;

my $active = 'initial';

sub build_writer {
    my %args = @_;
    my $output;
    my $writer = {};
    $writer->{set_output} = sub {
        $output = $_[0];
        die "unsupported encoding\n" if $args{bad};
    };
    $writer->{get_output} = sub { $output };
    bless $writer, 'EvalCaptureWriter';
    $writer->{set_output}->($args{output});
    return $writer;
}

sub initialize_writer {
    my %args = @_;
    defined($active = build_writer(%args)) or die 'writer construction failed';
}

eval { initialize_writer(bad => 1, output => 'discarded') };
like $@, qr/unsupported encoding/, 'constructor exception is caught by eval';

$active = 'replacement';
initialize_writer(output => 'current');
isa_ok $active, 'EvalCaptureWriter',
    'eval cleanup preserves captures of an installed subroutine';
is $active->{get_output}->(), 'current',
    'a later assignment reaches the original captured lexical';
