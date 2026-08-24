use strict;
use warnings;
use threads;
use Test::More;
use lib 'src/test/resources/unit/lib';

{
    use RegexImplementationCname;
    BEGIN { $RegexImplementationCname::Evil = 'A' }

    sub literal_results {
        my $quoted = qr/^\N{foo}$/;
        return [
            'foo' =~ /^\N{foo}$/ ? 1 : 0,
            'foo' =~ $quoted ? 1 : 0,
            'xy' =~ 'x\N{EMPTY-STR}y' ? 1 : 0,
            'A' =~ /^\N{EVIL}$/ ? 1 : 0,
        ];
    }

    my $direct = literal_results();
    my $before_thread = $RegexImplementationCname::Evil;
    my $threaded = threads->create(\&literal_results)->join;

    is_deeply($direct, [1, 1, 1, 1],
        'direct CV uses its lexical named-character results');
    is_deeply($threaded, $direct,
        'ithread CV uses the identical pre-resolved named-character results');
    is($RegexImplementationCname::Evil, $before_thread,
        'executing the CV in an ithread does not rerun the lexical translator');
}

done_testing;
