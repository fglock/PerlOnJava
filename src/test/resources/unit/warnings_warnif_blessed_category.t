use strict;
use warnings;
use Test::More tests => 4;

package LocalWarningCategory;
use warnings::register;
use overload '""' => sub { 'LocalWarningCategory=overloaded' }, fallback => 1;

sub emit_warning {
    my ($self) = @_;
    warnings::warnif($self, 'registered category warning');
}

sub emit_string_category {
    warnings::warnif('LocalWarningCategory', 'string category warning');
}

package main;

my $category = bless {}, 'LocalWarningCategory';
my @warnings;
{
    local $SIG{__WARN__} = sub { push @warnings, @_ };
    LocalWarningCategory::emit_warning($category);
}
is(scalar @warnings, 1, 'blessed category emits one warning');
like($warnings[0], qr/registered category warning/,
    'blessed category warning keeps its message');

@warnings = ();
{
    no warnings 'LocalWarningCategory';
    local $SIG{__WARN__} = sub { push @warnings, @_ };
    LocalWarningCategory::emit_warning($category);
}
is(scalar @warnings, 0, 'blessed category respects lexical suppression');

@warnings = ();
{
    local $SIG{__WARN__} = sub { push @warnings, @_ };
    LocalWarningCategory::emit_string_category();
}
is(scalar @warnings, 1, 'string category names remain supported');
