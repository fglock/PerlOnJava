use strict;
use warnings;
use Test::More;

package NativeWarningOrigin;
use warnings::register;

sub emit_warning {
    warnings::warn('native warning origin');
}

package main;

my @source;
{
    local $SIG{__WARN__} = sub {
        my $level = 0;
        while (1) {
            my @caller = caller($level);
            $level = $level + 1;
            last unless @caller;
            next if $caller[0] =~ /^(?:Carp|warnings)$/;
            @source = @caller[0, 1, 2];
            last;
        }
    };
    NativeWarningOrigin::emit_warning();
}

is($source[0], 'NativeWarningOrigin', 'warning handler sees the originating Perl package');
like($source[1], qr/native_module_warning_caller\.t$/, 'warning handler sees the originating Perl file');
ok($source[2] > 0, 'warning handler sees a real Perl source line');

done_testing;
