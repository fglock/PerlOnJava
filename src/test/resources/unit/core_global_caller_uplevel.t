use strict;
use warnings;
use Carp qw(carp croak);
use Test::More tests => 5;
use Sub::Uplevel qw(uplevel);

our $caller_uplevel_frame;

sub caller_uplevel_target {
    $caller_uplevel_frame = [caller];
}

sub caller_uplevel_wrapper {
    uplevel 1, \&caller_uplevel_target;
}

my $expected_line = __LINE__ + 1;
caller_uplevel_wrapper();
is($caller_uplevel_frame->[2], $expected_line,
    'caller observes the source line above a Sub::Uplevel wrapper');

my $callback_error_line = __LINE__ + 3;
eval {
    uplevel 1, sub {
        croak 'failure from an anonymous callback';
    };
};
like($@, qr/ at \Q$0\E line $callback_error_line\./,
    'Carp reports the final executable line in an anonymous Sub::Uplevel callback');

my (@warnings, @expected_warning_lines);
{
    local $SIG{__WARN__} = sub { push @warnings, @_ };
    uplevel 1, sub {
        push @expected_warning_lines, __LINE__ + 1;
        carp 'repeated warning';
        push @expected_warning_lines, __LINE__ + 1;
        carp 'repeated warning';
    };
}
is(scalar @warnings, 2, 'warnings at distinct callback lines remain distinct');
for my $index (0 .. 1) {
    like($warnings[$index],
        qr/ at \Q$0\E line \Q$expected_warning_lines[$index]\E\./,
        "Carp preserves anonymous callback callsite $index through Sub::Uplevel");
}
