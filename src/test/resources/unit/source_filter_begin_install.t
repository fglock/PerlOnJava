use strict;
use warnings;
use Test::More tests => 1;
use Filter::Util::Call ();

our @record;
push @record, 1;
BEGIN {
    Filter::Util::Call::filter_add(sub {
        $_ = 'push @main::record, 2;';
        Filter::Util::Call::filter_del();
        return 1;
    });
}
push @record, 3;

is_deeply(\@record, [1, 2, 3], 'BEGIN-installed filter injects at the runtime boundary');
