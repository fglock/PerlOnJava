use strict;
use warnings;
use Test::More tests => 1;

{
    package CallerLineFilter;
    use Filter::Util::Call;

    sub import {
        my $done = 0;
        filter_add(sub {
            return 0 if $done;
            my ($data, $end) = ('', '');
            while (my $status = filter_read()) {
                return $status if $status < 0;
                if (/^__(?:END|DATA)__\r?$/) {
                    $end = $_;
                    last;
                }
                $data .= $_;
                $_ = '';
            }
            $_ = "use strict;use warnings;$data$end";
            $done = 1;
        });
    }
}
BEGIN { $INC{'CallerLineFilter.pm'} = __FILE__ }

use CallerLineFilter;

sub caller_line {
    my @caller = caller(1);
    return $caller[2];
}

sub report_line {
    return caller_line();
}

my $got = report_line();
is($got, 41, 'source-filtered caller line numbers keep the use-statement newline');
