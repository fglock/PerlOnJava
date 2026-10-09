use strict;
use warnings;
use Carp qw(carp croak);
use Test::More tests => 5;

our $caller_override_extra = 0;
BEGIN {
    no warnings 'redefine';
    *CORE::GLOBAL::caller = sub (;$) {
        my ($height) = @_;
        $height += 1 + ($caller_override_extra || 0);
        my @caller = CORE::caller($height);
        return wantarray ? @caller : $caller[0];
    };
}

our $caller_uplevel_frame;

sub caller_uplevel_target {
    $caller_uplevel_frame = [caller];
}

sub caller_uplevel_wrapper {
    local $caller_override_extra = 1;
    $_[0]->();
}

my $expected_line = __LINE__ + 1;
caller_uplevel_wrapper(\&caller_uplevel_target);
is($caller_uplevel_frame->[2], $expected_line,
    'caller override reports the source line above a wrapper');

my $callback_error_line = __LINE__ + 3;
eval {
    caller_uplevel_wrapper(sub {
        croak 'failure from an anonymous callback';
    });
};
like($@, qr/ at \Q$0\E line $callback_error_line\./,
    'Carp reports the line in an anonymous callback with a caller override');

my (@warnings, @expected_warning_lines);
{
    local $SIG{__WARN__} = sub { push @warnings, @_ };
    caller_uplevel_wrapper(sub {
        push @expected_warning_lines, __LINE__ + 1;
        carp 'repeated warning';
        push @expected_warning_lines, __LINE__ + 1;
        carp 'repeated warning';
    });
}
is(scalar @warnings, 2, 'warnings at distinct callback lines remain distinct');
for my $index (0 .. 1) {
    like($warnings[$index],
        qr/ at \Q$0\E line \Q$expected_warning_lines[$index]\E\./,
        "Carp preserves anonymous callback callsite $index with a caller override");
}
