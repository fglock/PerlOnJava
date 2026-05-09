use strict;
use warnings;
use Test::More tests => 7;

{
    our sub const;
    BEGIN { delete $::{const} }
    use constant const => 3;

    is const, 3, 'our sub tombstone is repinned when use constant defines CODE';
    ok defined(&const), 'constant CODE slot is defined after tombstoned stub';
}

{
    my $die = sub { die "generator boom\n" };
    my $state = [];
    local @INC = (sub { return ($die, $state) });

    my ($result, $err);
    my $outer = eval {
        $result = do "pr694_missing.pm";
        $err = $@;
        1;
    };

    ok $outer, 'do FILE traps @INC generator die';
    ok !defined($result), 'do FILE returns undef after @INC generator die';
    like $err, qr/generator boom/, 'do FILE stores @INC generator die in $@';
}

{
    my $ok = eval q{
        BEGIN {
            package PR694::EvalExporter;
            sub import {
                my $target = caller;
                no strict 'refs';
                *{"${target}::has"} = sub { 1 };
            }
            sub unimport {
                my $target = caller;
                no strict 'refs';
                delete ${"${target}::"}{has};
            }
            $INC{'PR694/EvalExporter.pm'} = 1;
        }

        package PR694::EvalConsumer;
        use PR694::EvalExporter;
        has foo => (is => 'ro');
        no PR694::EvalExporter;
        1;
    };
    ok $ok, 'string eval call keeps imported CV after no deletes stash entry'
        or diag $@;
}

{
    my $ok = eval q{
        BEGIN {
            package PR694::EvalExporterAgain;
            sub import {
                my $target = caller;
                no strict 'refs';
                *{"${target}::has"} = sub { 1 };
            }
            sub unimport {
                my $target = caller;
                no strict 'refs';
                delete ${"${target}::"}{has};
            }
            $INC{'PR694/EvalExporterAgain.pm'} = 1;
        }

        package PR694::EvalConsumerAgain;
        use PR694::EvalExporterAgain;
        has foo => (is => 'ro');
        no PR694::EvalExporterAgain;
        use PR694::EvalExporterAgain;
        has foo2 => (is => 'ro');
        no PR694::EvalExporterAgain;
        1;
    };
    ok $ok, 'string eval call keeps re-imported CV across no/use cycle'
        or diag $@;
}
