import { fireEvent, render, screen, waitFor } from '../../test-utils/rtl';
import '@testing-library/jest-dom';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import MessagingLink from '../../components/devices/MessagingLink';
import MessagingApprove from '../../components/devices/MessagingApprove';
import LinkDevice from '../../components/LinkDevice';
import QrCode from '../../components/devices/QrCode';
import { approveLink, BadLinkCodeError, receiveLink, startLink } from '../../components/devices/messagingFlows';
import { useMessagingState } from '../../components/devices/useMessagingKeys';
import { cryptoApi } from '../../services/cryptoApi';

jest.mock('react-i18next', () => ({
  useTranslation: () => ({ t: (key) => key, i18n: { language: 'en' } }),
}));
jest.mock('react-toastify', () => ({ toast: { success: jest.fn(), error: jest.fn() } }));
jest.mock('../../services/cryptoApi', () => ({ cryptoApi: { cancelLink: jest.fn() } }));
jest.mock('../../components/devices/useMessagingKeys', () => ({ useMessagingState: jest.fn() }));
jest.mock('../../components/devices/messagingFlows', () => {
  class BadLinkCodeError extends Error {}
  return {
    startLink: jest.fn(), receiveLink: jest.fn(), registerThisBrowser: jest.fn(), approveLink: jest.fn(), BadLinkCodeError,
  };
});

const started = (expiresAt = new Date(Date.now() + 300000).toISOString()) =>
  ({ id: 'link-1', code: 'link-1.KEY.SECRET', expiresAt });

beforeEach(() => {
  jest.clearAllMocks();
  cryptoApi.cancelLink.mockResolvedValue(true);
});

describe('linking this browser', () => {
  test('shows the code and a QR, waits for the other device and registers this browser once it answers', async () => {
    startLink.mockResolvedValue(started());
    receiveLink.mockResolvedValueOnce(false).mockResolvedValueOnce(true);
    const onRegister = jest.fn().mockResolvedValue();
    const onClose = jest.fn();
    render(<MessagingLink onRegister={onRegister} onClose={onClose} pollMs={5} />);

    expect(await screen.findByTestId('link-code')).toHaveTextContent('link-1.KEY.SECRET');
    expect(screen.getByRole('img', { name: 'devices.messaging.link.qr' })).toBeInTheDocument();
    expect(screen.getByRole('status')).toHaveTextContent('devices.messaging.link.waiting');

    await waitFor(() => expect(onRegister).toHaveBeenCalledTimes(1));
    expect(receiveLink).toHaveBeenCalledTimes(2);
    expect(onClose).toHaveBeenCalled();
  });

  test('copies the code', async () => {
    startLink.mockResolvedValue(started());
    receiveLink.mockResolvedValue(false);
    const writeText = jest.fn().mockResolvedValue();
    Object.assign(navigator, { clipboard: { writeText } });
    render(<MessagingLink onRegister={jest.fn()} onClose={jest.fn()} pollMs={1000} />);

    fireEvent.click(await screen.findByRole('button', { name: 'devices.messaging.link.copy' }));

    await waitFor(() => expect(writeText).toHaveBeenCalledWith('link-1.KEY.SECRET'));
    expect(await screen.findByRole('button', { name: 'devices.messaging.link.copied' })).toBeInTheDocument();
  });

  test('a clipboard that refuses leaves the button as it was', async () => {
    startLink.mockResolvedValue(started());
    receiveLink.mockResolvedValue(false);
    Object.assign(navigator, { clipboard: { writeText: jest.fn().mockRejectedValue(new Error('denied')) } });
    render(<MessagingLink onRegister={jest.fn()} onClose={jest.fn()} pollMs={1000} />);

    fireEvent.click(await screen.findByRole('button', { name: 'devices.messaging.link.copy' }));

    await waitFor(() => expect(navigator.clipboard.writeText).toHaveBeenCalled());
    expect(screen.getByRole('button', { name: 'devices.messaging.link.copy' })).toBeInTheDocument();
  });

  test('says so when no code can be made, and a second try starts over', async () => {
    startLink.mockRejectedValueOnce(new Error('down')).mockResolvedValueOnce(started());
    receiveLink.mockResolvedValue(false);
    render(<MessagingLink onRegister={jest.fn()} onClose={jest.fn()} pollMs={1000} />);

    expect(await screen.findByRole('alert')).toHaveTextContent('devices.messaging.link.failed.start');
    fireEvent.click(screen.getByRole('button', { name: 'devices.messaging.link.again' }));

    expect(await screen.findByTestId('link-code')).toBeInTheDocument();
    expect(startLink).toHaveBeenCalledTimes(2);
  });

  test('a code that ran out is reported', async () => {
    startLink.mockResolvedValue(started(new Date(Date.now() - 1000).toISOString()));
    render(<MessagingLink onRegister={jest.fn()} onClose={jest.fn()} pollMs={5} />);

    expect(await screen.findByRole('alert')).toHaveTextContent('devices.messaging.link.failed.expired');
    expect(receiveLink).not.toHaveBeenCalled();
  });

  test('an answer this browser cannot use is reported', async () => {
    startLink.mockResolvedValue(started());
    receiveLink.mockRejectedValue(new Error('bad answer'));
    render(<MessagingLink onRegister={jest.fn()} onClose={jest.fn()} pollMs={5} />);

    expect(await screen.findByRole('alert')).toHaveTextContent('devices.messaging.link.failed.finish');
  });

  test('closing the dialog cancels the code on the server', async () => {
    startLink.mockResolvedValue(started());
    receiveLink.mockResolvedValue(false);
    const { unmount } = render(<MessagingLink onRegister={jest.fn()} onClose={jest.fn()} pollMs={1000} />);
    await screen.findByTestId('link-code');

    unmount();

    expect(cryptoApi.cancelLink).toHaveBeenCalledWith('link-1');
  });

  test('a dialog closed before the code arrives cancels it as soon as it does', async () => {
    let resolve;
    startLink.mockReturnValue(new Promise((done) => { resolve = done; }));
    const { unmount } = render(<MessagingLink onRegister={jest.fn()} onClose={jest.fn()} pollMs={1000} />);
    unmount();

    resolve(started());

    await waitFor(() => expect(cryptoApi.cancelLink).toHaveBeenCalledWith('link-1'));
  });
});

describe('approving another device', () => {
  test('hands the identity over for the code that was entered', async () => {
    approveLink.mockResolvedValue();
    const onClose = jest.fn();
    render(<MessagingApprove onClose={onClose} />);
    const submit = screen.getByRole('button', { name: 'devices.messaging.approve.submit' });
    expect(submit).toBeDisabled();

    fireEvent.change(screen.getByLabelText('devices.messaging.approve.label'), { target: { value: 'the-code' } });
    fireEvent.click(submit);

    await waitFor(() => expect(approveLink).toHaveBeenCalledWith('the-code'));
    await waitFor(() => expect(onClose).toHaveBeenCalled());
  });

  test('a malformed code and a refusal from the server are explained and keep the dialog open', async () => {
    approveLink.mockRejectedValueOnce(new BadLinkCodeError()).mockRejectedValueOnce(new Error(''));
    const onClose = jest.fn();
    render(<MessagingApprove initialCode="oops" onClose={onClose} />);

    fireEvent.click(screen.getByRole('button', { name: 'devices.messaging.approve.submit' }));
    expect(await screen.findByRole('alert')).toHaveTextContent('devices.messaging.approve.badCode');

    fireEvent.click(screen.getByRole('button', { name: 'devices.messaging.approve.submit' }));
    await waitFor(() => expect(screen.getByRole('alert')).toHaveTextContent('devices.messaging.errors.generic'));
    expect(onClose).not.toHaveBeenCalled();
  });
});

describe('the link page', () => {
  const renderAt = (hash) => render(
    <MemoryRouter initialEntries={[`/link${hash}`]}>
      <Routes>
        <Route path="/link" element={<LinkDevice />} />
        <Route path="/sessions" element={<p>sessions page</p>} />
      </Routes>
    </MemoryRouter>,
  );

  test('opens the approval with the code from the address', () => {
    useMessagingState.mockReturnValue({ state: 'ready' });

    renderAt('#id.KEY.SECRET');

    expect(screen.getByLabelText('devices.messaging.approve.label')).toHaveValue('id.KEY.SECRET');
  });

  test('closing it goes to the devices page', async () => {
    useMessagingState.mockReturnValue({ state: 'ready' });
    renderAt('');

    fireEvent.click(screen.getByRole('button', { name: 'devices.messaging.buttons.cancel' }));

    expect(await screen.findByText('sessions page')).toBeInTheDocument();
  });

  test.each([
    ['elsewhere', 'devices.messaging.approve.notReady'],
    ['notSetUp', 'devices.messaging.approve.notReady'],
    ['off', 'messages.gate.off'],
  ])('a browser without keys (%s) is told what to do first', (state, message) => {
    useMessagingState.mockReturnValue({ state });

    renderAt('#id.KEY.SECRET');

    expect(screen.getByText(message)).toBeInTheDocument();
    expect(screen.queryByLabelText('devices.messaging.approve.label')).not.toBeInTheDocument();
  });

  test('waits while the keys load', () => {
    useMessagingState.mockReturnValue({ state: 'loading' });

    renderAt('');

    expect(screen.getByText('app.loading')).toBeInTheDocument();
  });
});

describe('the QR code', () => {
  test('draws one dark square per module of the encoded text', () => {
    const { container } = render(<QrCode value="/link#abc" label="QR" />);

    const path = container.querySelector('path');
    expect(screen.getByRole('img', { name: 'QR' })).toBeInTheDocument();
    expect(path.getAttribute('d')).toMatch(/^M\d+ \d+h1v1h-1z/);
  });
});
