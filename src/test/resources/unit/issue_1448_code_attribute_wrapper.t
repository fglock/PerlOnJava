use strict;
use warnings;
use Test::More;

{
    package Local::AuditWrapper;
    our $calls = 0;

    sub MODIFY_CODE_ATTRIBUTES {
        my ($package, $code, @attributes) = @_;
        return @attributes unless @attributes == 1 && $attributes[0] eq 'Audit';

        no strict 'refs';
        no warnings 'redefine';
        *{"${package}::run"} = sub {
            my @out = $code->(@_);
            return wantarray ? @out : shift @out;
        };
        return ();
    }

    sub run : Audit {
        ++$calls;
        return wantarray ? ('first', 'second') : 'scalar';
    }
}

is(scalar Local::AuditWrapper::run(), 'first', 'audit wrapper returns the first list value in scalar context');
is($Local::AuditWrapper::calls, 1, 'scalar call reaches the original sub once');

my @result = Local::AuditWrapper::run();
is_deeply(\@result, ['first', 'second'], 'audit wrapper preserves list context');
is($Local::AuditWrapper::calls, 2, 'list call reaches the original sub once');

done_testing;
