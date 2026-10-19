package Issue1785::Source;
use strict;
use warnings;
use constant ARG => 99999;

package Issue1785::MethodMessages;
use strict;
use warnings;

my (%by_name, %by_id);

sub add_message {
    my ($class, $name, $id) = @_;
    {
        no strict 'refs';
        *{"${class}::$name"} = sub () { $id };
        # my $error;
        # eval "package $class; use constant $name => $id;";
    }
    $by_id{$id} = $name;
    $by_name{$name} = $id;
}

BEGIN {
    my $code = Issue1785::Source->can('ARG');
    __PACKAGE__->add_message('GENERATED_ISSUE_1785', $code->());
}

1;
